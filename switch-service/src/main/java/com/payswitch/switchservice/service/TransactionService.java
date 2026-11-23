package com.payswitch.switchservice.service;

import com.payswitch.common.PaymentLimits;
import com.payswitch.observability.TxnMdc;
import com.payswitch.switchservice.db.TransactionRepository;
import com.payswitch.switchservice.db.TxnEventRepository;
import com.payswitch.switchservice.domain.Txn;
import com.payswitch.switchservice.domain.TxnEvent;
import com.payswitch.switchservice.domain.TxnState;
import com.payswitch.switchservice.domain.TxnStateMachine;
import com.payswitch.switchservice.live.TxnStateChanged;
import com.payswitch.switchservice.metrics.SwitchMetrics;
import com.payswitch.switchservice.routing.BankRoute;
import com.payswitch.switchservice.routing.BankRouter;
import com.payswitch.switchservice.routing.Vpa;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only door through which a payment's state may change.
 *
 * <p>Every change does two writes in ONE database transaction: update the transactions row
 * and insert a txn_events row. Either both are saved or neither is, so the current state and
 * the history can never disagree.
 */
@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    public static final String REASON_CREATED = "CREATED";
    public static final String REASON_UNKNOWN_HANDLE = "UNKNOWN_HANDLE";

    private final TransactionRepository txns;
    private final TxnEventRepository events;
    private final BankRouter router;
    private final SwitchMetrics metrics;
    private final ApplicationEventPublisher publisher;

    public TransactionService(TransactionRepository txns, TxnEventRepository events, BankRouter router,
                              SwitchMetrics metrics, ApplicationEventPublisher publisher) {
        this.txns = txns;
        this.events = events;
        this.router = router;
        this.metrics = metrics;
        this.publisher = publisher;
    }

    /**
     * Records a new payment in state INITIATED. If either VPA's handle belongs to no known bank,
     * the payment is still recorded (so there is a trace of the attempt) and moved to FAILED.
     *
     * @param amount paise
     * @throws IllegalArgumentException      malformed input: nothing is saved
     * @throws DuplicateTransactionException the txnId already exists
     */
    @Transactional
    public Txn create(String txnId, String payerVpa, String payeeVpa, long amount) {
        if (txnId == null || txnId.isBlank() || txnId.length() > 64) {
            throw new IllegalArgumentException("txnId must be 1 to 64 characters");
        }
        if (amount <= 0 || amount > PaymentLimits.MAX_AMOUNT_PAISE) {
            throw new IllegalArgumentException("amount must be between 1 and "
                    + PaymentLimits.MAX_AMOUNT_PAISE + " paise, got " + amount);
        }
        Vpa payer = Vpa.parse(payerVpa);
        Vpa payee = Vpa.parse(payeeVpa);
        if (payer.equals(payee)) {
            throw new IllegalArgumentException("payer and payee must be different");
        }

        Optional<BankRoute> payerBank = router.route(payer);
        Optional<BankRoute> payeeBank = router.route(payee);

        boolean inserted = txns.insert(txnId, payer.address(), payee.address(),
                payerBank.map(BankRoute::code).orElse(null),
                payeeBank.map(BankRoute::code).orElse(null),
                amount, TxnState.INITIATED);
        if (!inserted) {
            throw new DuplicateTransactionException(txnId);
        }
        events.insert(txnId, null, TxnState.INITIATED, REASON_CREATED);
        publisher.publishEvent(new TxnStateChanged(txnId, payer.address(), payee.address(), amount, null,
                TxnState.INITIATED.name(), REASON_CREATED, Instant.now()));
        TxnMdc.state(txnId, TxnState.INITIATED.name());
        log.info("Txn {}: created, {} -> {}", txnId, payerBank.map(BankRoute::code).orElse("?"),
                payeeBank.map(BankRoute::code).orElse("?"));

        if (payerBank.isEmpty() || payeeBank.isEmpty()) {
            return transition(txnId, TxnState.FAILED, REASON_UNKNOWN_HANDLE);
        }
        return get(txnId);
    }

    /**
     * Moves the payment to {@code newState}, based on whatever state it is in right now.
     *
     * @throws com.payswitch.switchservice.domain.IllegalStateTransitionException move not allowed
     * @throws ConcurrentTransitionException someone else changed it between our read and our write
     */
    @Transactional
    public Txn transition(String txnId, TxnState newState, String reason) {
        return apply(get(txnId), newState, reason);
    }

    /**
     * Same, but the caller says which version it based its decision on. Typical use: read the
     * payment (version 3), call a bank for two seconds, then come back and say "move it to
     * DEBITED, if it is still version 3". If a recovery job touched it meanwhile, this refuses.
     */
    @Transactional
    public Txn transition(String txnId, long expectedVersion, TxnState newState, String reason) {
        Txn current = get(txnId);
        if (current.version() != expectedVersion) {
            throw new ConcurrentTransitionException(txnId, expectedVersion);
        }
        return apply(current, newState, reason);
    }

    /**
     * Records something that happened WITHOUT changing the state, e.g. "the debit call came back
     * UNKNOWN: timeout". The event's from and to are both the current state. This is not a
     * transition, so the state machine's "no self-moves" rule is not involved. The version still
     * goes up, so anyone holding an older snapshot will notice the payment was touched.
     */
    @Transactional
    public Txn note(String txnId, long expectedVersion, String reason) {
        validateReason(reason);
        Txn current = get(txnId);
        if (current.version() != expectedVersion || txns.touch(txnId, expectedVersion) == 0) {
            throw new ConcurrentTransitionException(txnId, expectedVersion);
        }
        events.insert(txnId, current.state(), current.state(), reason);
        return get(txnId);
    }

    /**
     * Claims a batch of stuck payments for one resolver: lock (skipping rows another resolver is
     * locking right now), lease, commit. One short transaction, no bank calls inside.
     */
    @Transactional
    public List<String> claimStuck(Collection<TxnState> states, Duration stuckFor, int batchSize, Duration lease) {
        return txns.claimStuck(states, stuckFor, batchSize, lease);
    }

    /** Keeps the background job away from this payment for {@code lease}. */
    @Transactional
    public void lease(String txnId, Duration lease) {
        txns.lease(txnId, lease);
    }

    /** For reconciliation: payments created in [from, to) that are at least {@code cutoff} old. */
    @Transactional(readOnly = true)
    public List<Txn> createdBetween(Instant from, Instant to, Instant youngest) {
        return txns.createdBetween(from, to, youngest);
    }

    @Transactional(readOnly = true)
    public int countTooRecent(Instant from, Instant to, Instant youngest) {
        return txns.countTooRecent(from, to, youngest);
    }

    /** The database's clock (the one that stamps created_at). */
    @Transactional(readOnly = true)
    public Instant dbNow() {
        return txns.dbNow();
    }

    /** Which of these txnIds exist in the switch at all. */
    @Transactional(readOnly = true)
    public Set<String> existing(Collection<String> txnIds) {
        return txns.existing(txnIds);
    }

    @Transactional(readOnly = true)
    public Txn get(String txnId) {
        return txns.find(txnId).orElseThrow(() -> new TransactionNotFoundException(txnId));
    }

    /** The full story of one payment, oldest event first. */
    @Transactional(readOnly = true)
    public List<TxnEvent> history(String txnId) {
        return events.findByTxnId(txnId);
    }

    private static void validateReason(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 100) {
            throw new IllegalArgumentException("reason must be 1 to 100 characters");
        }
    }

    private Txn apply(Txn current, TxnState newState, String reason) {
        validateReason(reason);
        // 1. Is this move in the rulebook?
        TxnStateMachine.check(current.txnId(), current.state(), newState);

        // 2. Write the new state, but only if nobody changed the row since we read it.
        if (txns.updateState(current.txnId(), current.version(), newState) == 0) {
            throw new ConcurrentTransitionException(current.txnId(), current.version());
        }

        // 3. Record what happened. Same DB transaction as step 2.
        events.insert(current.txnId(), current.state(), newState, reason);

        // 4. Tell the live monitor. Listeners use @TransactionalEventListener(AFTER_COMMIT), so this
        //    is only DELIVERED once this transaction commits; if it rolls back, nobody hears of it.
        publisher.publishEvent(new TxnStateChanged(current.txnId(), current.payerVpa(), current.payeeVpa(),
                current.amount(), current.state().name(), newState.name(), reason, Instant.now()));

        // 5. Observability: the log line carries txnId and the NEW state; a final state is counted
        //    once the transaction has committed.
        TxnMdc.state(current.txnId(), newState.name());
        log.info("Txn {}: {} -> {} ({})", current.txnId(), current.state(), newState, reason);
        if (TxnStateMachine.isTerminal(newState)) {
            metrics.paymentFinished(newState);
        }
        return get(current.txnId());
    }
}

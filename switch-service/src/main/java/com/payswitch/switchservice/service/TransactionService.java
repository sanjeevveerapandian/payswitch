package com.payswitch.switchservice.service;

import com.payswitch.common.bank.BankTxnRequest;
import com.payswitch.switchservice.db.TransactionRepository;
import com.payswitch.switchservice.db.TxnEventRepository;
import com.payswitch.switchservice.domain.Txn;
import com.payswitch.switchservice.domain.TxnEvent;
import com.payswitch.switchservice.domain.TxnState;
import com.payswitch.switchservice.domain.TxnStateMachine;
import com.payswitch.switchservice.routing.BankRoute;
import com.payswitch.switchservice.routing.BankRouter;
import com.payswitch.switchservice.routing.Vpa;
import java.util.List;
import java.util.Optional;
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

    public static final String REASON_CREATED = "CREATED";
    public static final String REASON_UNKNOWN_HANDLE = "UNKNOWN_HANDLE";

    private final TransactionRepository txns;
    private final TxnEventRepository events;
    private final BankRouter router;

    public TransactionService(TransactionRepository txns, TxnEventRepository events, BankRouter router) {
        this.txns = txns;
        this.events = events;
        this.router = router;
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
        if (amount <= 0 || amount > BankTxnRequest.MAX_AMOUNT_PAISE) {
            throw new IllegalArgumentException("amount must be between 1 and "
                    + BankTxnRequest.MAX_AMOUNT_PAISE + " paise, got " + amount);
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

    @Transactional(readOnly = true)
    public Txn get(String txnId) {
        return txns.find(txnId).orElseThrow(() -> new TransactionNotFoundException(txnId));
    }

    /** The full story of one payment, oldest event first. */
    @Transactional(readOnly = true)
    public List<TxnEvent> history(String txnId) {
        return events.findByTxnId(txnId);
    }

    private Txn apply(Txn current, TxnState newState, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 100) {
            throw new IllegalArgumentException("reason must be 1 to 100 characters");
        }
        // 1. Is this move in the rulebook?
        TxnStateMachine.check(current.txnId(), current.state(), newState);

        // 2. Write the new state, but only if nobody changed the row since we read it.
        if (txns.updateState(current.txnId(), current.version(), newState) == 0) {
            throw new ConcurrentTransitionException(current.txnId(), current.version());
        }

        // 3. Record what happened. Same DB transaction as step 2.
        events.insert(current.txnId(), current.state(), newState, reason);
        return get(current.txnId());
    }
}

package com.payswitch.switchservice.service;

import static com.payswitch.switchservice.bank.BankCallOutcome.DECLINED;
import static com.payswitch.switchservice.bank.BankCallOutcome.SUCCESS;
import static com.payswitch.switchservice.bank.BankCallOutcome.UNKNOWN;

import com.payswitch.common.bank.CreditRequest;
import com.payswitch.common.bank.DebitRequest;
import com.payswitch.common.bank.DeclineReason;
import com.payswitch.common.bank.OpType;
import com.payswitch.common.bank.ReverseRequest;
import com.payswitch.observability.TxnMdc;
import com.payswitch.switchservice.bank.BankCallResult;
import com.payswitch.switchservice.bank.BankClient;
import com.payswitch.switchservice.domain.Txn;
import com.payswitch.switchservice.domain.TxnState;
import com.payswitch.switchservice.metrics.SwitchMetrics;
import com.payswitch.switchservice.retry.Retrier;
import com.payswitch.switchservice.routing.BankRoute;
import com.payswitch.switchservice.routing.BankRouter;
import com.payswitch.switchservice.routing.Vpa;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Every step of a payment, in one place. Two ways to drive it:
 *
 * <ul>
 *   <li>{@link #firstAttempt}: one call per step, as in Phase 4/5. An UNKNOWN answer is recorded
 *       and the payment is left where it is.</li>
 *   <li>{@link #resolve}: picks up a payment wherever it stopped and finds out the truth, using
 *       status checks with fencing and retries. Used inline by POST /pay and by the ResolverJob.</li>
 * </ul>
 *
 * <p>Not @Transactional: every state change is its own short transaction in TransactionService,
 * and no database transaction is held open while a bank is being called.
 */
@Service
public class PaymentFlow {

    private static final Logger log = LoggerFactory.getLogger(PaymentFlow.class);

    /** States that mean "a request went out and we are waiting to learn what happened". */
    public static final Set<TxnState> RESOLVABLE =
            EnumSet.of(TxnState.DEBIT_SENT, TxnState.CREDIT_SENT, TxnState.REVERSAL_SENT);

    /**
     * Everything the background job must pick up if it has not moved for a while: the RESOLVABLE
     * states, plus
     * <ul>
     *   <li>DEBITED: a crash between saving DEBITED and saving CREDIT_SENT leaves the payer's money
     *       in transit with nobody carrying it on;</li>
     *   <li>INITIATED: a crash right after create() leaves a payment no bank has ever heard of.</li>
     * </ul>
     */
    public static final Set<TxnState> STUCK = EnumSet.of(TxnState.INITIATED, TxnState.DEBIT_SENT,
            TxnState.DEBITED, TxnState.CREDIT_SENT, TxnState.REVERSAL_SENT);

    /** Decline reasons on a REVERSE that prove the payer's money never left. */
    private static final Set<DeclineReason> NOTHING_TO_REVERSE =
            Set.of(DeclineReason.ORIGINAL_NOT_FOUND, DeclineReason.ORIGINAL_NOT_SUCCESSFUL);

    private final TransactionService txns;
    private final BankRouter router;
    private final BankClient banks;
    private final Retrier retrier;
    private final SwitchMetrics metrics;

    public PaymentFlow(TransactionService txns, BankRouter router, BankClient banks, Retrier retrier,
                       SwitchMetrics metrics) {
        this.txns = txns;
        this.router = router;
        this.banks = banks;
        this.retrier = retrier;
        this.metrics = metrics;
    }

    // =========================================================================================
    // First attempt (unchanged behaviour from Phase 5)
    // =========================================================================================

    /** Debit, then credit, then (only if the credit is declined) one reversal. */
    public void firstAttempt(String txnId) {
        try (TxnMdc.Scope mdc = TxnMdc.txn(txnId)) {
            firstAttemptInScope(txnId);
        }
    }

    private void firstAttemptInScope(String txnId) {
        Txn txn = txns.get(txnId);

        txn = move(txn, TxnState.DEBIT_SENT, "DEBIT_REQUESTED:" + txn.payerBank());
        BankCallResult debit = banks.debit(payerBank(txn),
                new DebitRequest(txn.txnId(), txn.payerVpa(), txn.amount()));
        switch (debit.outcome()) {
            case DECLINED -> {
                move(txn, TxnState.FAILED, "DEBIT_DECLINED:" + debit.detail());
                return;                                     // money never left the payer
            }
            case UNKNOWN -> {
                // Ravi MAY have been debited. Stay in DEBIT_SENT: that is exactly what we know.
                txns.note(txn.txnId(), txn.version(), "DEBIT_UNKNOWN: " + debit.detail());
                log.warn("Txn {}: debit outcome UNKNOWN ({}). Left in DEBIT_SENT.", txn.txnId(), debit.detail());
                return;
            }
            case SUCCESS -> txn = move(txn, TxnState.DEBITED, "DEBIT_OK");
        }

        txn = sendCredit(txn);
        if (txn.state() == TxnState.REVERSAL_SENT) {
            reverseOnce(txn);
        }
    }

    /** From DEBITED: send the credit once. Ends in SUCCESS, CREDIT_SENT (unknown) or REVERSAL_SENT. */
    private Txn sendCredit(Txn txn) {
        txn = move(txn, TxnState.CREDIT_SENT, "CREDIT_REQUESTED:" + txn.payeeBank());
        BankCallResult credit = banks.credit(payeeBank(txn),
                new CreditRequest(txn.txnId(), txn.payeeVpa(), txn.amount()));
        return switch (credit.outcome()) {
            case SUCCESS -> move(txn, TxnState.SUCCESS, "CREDIT_OK");
            case UNKNOWN -> {
                // Ravi WAS debited. Anu MAY have been credited. Do not reverse on a guess:
                // if Anu did get the money, a reversal would pay it out twice.
                log.warn("Txn {}: credit outcome UNKNOWN ({}). Left in CREDIT_SENT.", txn.txnId(), credit.detail());
                yield txns.note(txn.txnId(), txn.version(), "CREDIT_UNKNOWN: " + credit.detail());
            }
            case DECLINED -> move(txn, TxnState.REVERSAL_SENT, "CREDIT_DECLINED:" + credit.detail());
        };
    }

    /** One reversal call, answered the Phase 5 way. */
    private void reverseOnce(Txn txn) {
        BankCallResult reversal = banks.reverse(payerBank(txn), new ReverseRequest(txn.txnId()));
        if (isFinalReversal(reversal)) {
            finishReversal(txn, reversal);
        } else if (reversal.outcome() == DECLINED) {
            // Any other "no" does NOT prove the money is back. Ravi's money is still at risk.
            txns.note(txn.txnId(), txn.version(), "REVERSAL_DECLINED: " + reversal.detail());
            log.error("Txn {}: REVERSAL DECLINED ({}). Payer's money is NOT back. Needs attention.",
                    txn.txnId(), reversal.detail());
        } else {
            txns.note(txn.txnId(), txn.version(), "REVERSAL_UNKNOWN: " + reversal.detail());
            log.error("Txn {}: REVERSAL outcome UNKNOWN ({}). Payer's money may NOT be back. Needs attention.",
                    txn.txnId(), reversal.detail());
        }
    }

    // =========================================================================================
    // Resolution (Phase 6): find out what really happened, and finish the payment
    // =========================================================================================

    /**
     * Drives the payment forward from wherever it is until it reaches a final state or cannot make
     * progress right now. Safe to call more than once and from more than one place: if someone
     * else moves the payment while we work, the optimistic lock stops us and we leave it to them.
     */
    public void resolve(String txnId) {
        // Runs on the request thread, the inline-resolution pool, the ResolverJob and recon: set the
        // MDC here, so every one of those logs this payment's txnId.
        try (TxnMdc.Scope mdc = TxnMdc.txn(txnId)) {
            resolveInScope(txnId);
        }
    }

    private void resolveInScope(String txnId) {
        TxnState stuckIn = null;                              // where the payment was when we started
        TxnState reached = null;                              // where we left it
        try {
            for (int step = 0; step < 10; step++) {          // a payment needs at most ~5 steps
                Txn txn = txns.get(txnId);
                if (stuckIn == null) {
                    stuckIn = txn.state();
                    TxnMdc.state(txnId, stuckIn.name());
                }
                reached = txn.state();
                Txn after = switch (txn.state()) {
                    // DEBIT_SENT is saved BEFORE the debit goes out, so INITIATED means no bank was
                    // ever asked. Nothing can have moved: close it. (If the original thread is somehow
                    // still alive, its next move fails on the version check and it stops.)
                    case INITIATED -> move(txn, TxnState.FAILED, "NEVER_SENT");
                    case DEBIT_SENT -> resolveDebit(txn);
                    case DEBITED -> sendCredit(txn);          // e.g. crashed between DEBITED and CREDIT_SENT
                    case CREDIT_SENT -> resolveCredit(txn);
                    case REVERSAL_SENT -> resolveReversal(txn);
                    case DEEMED_APPROVED -> recheckDeemed(txn);
                    default -> null;                          // final: nothing to do
                };
                if (after != null) {
                    reached = after.state();
                }
                if (after == null || after.state() == txn.state()) {
                    return;                                   // done, or nothing more to do right now
                }
            }
        } catch (ConcurrentTransitionException someoneElse) {
            log.info("Txn {}: changed by another resolver while we worked on it; leaving it to them.", txnId);
        } catch (Interrupted stop) {
            // The interrupt flag stays set for whoever called us.
            log.info("Txn {}: interrupted (shutting down?). Left as it was; it will be resolved after restart.", txnId);
        } finally {
            if (stuckIn != null && reached != null && reached != stuckIn) {
                metrics.resolverFixed(stuckIn);
            }
        }
    }

    /** Thrown when the thread is interrupted mid-retries: stop, decide nothing. */
    private static final class Interrupted extends RuntimeException {
        Interrupted() {
            super(null, null, false, false);
        }
    }

    /**
     * Asks the bank, with retries. If the retries were cut short by an interrupt (the switch is
     * shutting down), the last UNKNOWN is NOT evidence that the bank stopped answering: it only means
     * we stopped asking. So we refuse to return it, and nobody gets to decide on it.
     */
    private BankCallResult askWithRetries(BankRoute bank, String op, Supplier<BankCallResult> call,
                                          Predicate<BankCallResult> done) {
        AtomicInteger attempts = new AtomicInteger();
        BankCallResult result = retrier.run(() -> {
            attempts.incrementAndGet();
            return call.get();
        }, done);
        metrics.retries(bank.code(), op, attempts.get() - 1);
        if (Thread.currentThread().isInterrupted()) {
            throw new Interrupted();
        }
        return result;
    }

    /**
     * Debit outcome unknown. Ask the payer's bank, fencing the debit if it has no record.
     * <ul>
     *   <li>it happened -> DEBITED, and the payment carries on to the credit</li>
     *   <li>it did not happen (declined, or fenced just now) -> FAILED. The fence guarantees a late
     *       copy of the debit can never take Ravi's money afterwards.</li>
     *   <li>still no answer -> REVERSAL_SENT. Safe whether or not the debit happened: the bank
     *       either gives the money back or answers NO_DEBIT and blocks the debit forever.</li>
     * </ul>
     */
    private Txn resolveDebit(Txn txn) {
        BankCallResult check = askWithRetries(payerBank(txn), "STATUS_CHECK",
                () -> banks.statusCheckAndFence(payerBank(txn), txn.txnId(), OpType.DEBIT),
                r -> r.outcome() != UNKNOWN);
        return switch (check.outcome()) {
            case SUCCESS -> move(txn, TxnState.DEBITED, "DEBIT_CONFIRMED_BY_STATUS");
            case DECLINED -> move(txn, TxnState.FAILED, "DEBIT_DECLINED:" + check.detail());
            case UNKNOWN -> {
                log.warn("Txn {}: debit still unknown after {} status checks ({}). Reversing to be safe.",
                        txn.txnId(), retrier.maxAttempts(), check.detail());
                yield move(txn, TxnState.REVERSAL_SENT, "DEBIT_STATUS_UNKNOWN: " + check.detail());
            }
        };
    }

    /**
     * Credit outcome unknown. Ravi was debited for sure. Ask Anu's bank, fencing the credit.
     * <ul>
     *   <li>it happened -> SUCCESS</li>
     *   <li>it did not happen (declined, or fenced just now) -> REVERSAL_SENT, give Ravi his money back</li>
     *   <li>still no answer -> DEEMED_APPROVED. NEVER reverse here: if Anu did get the Rs 200 and
     *       we also refund Ravi, Rs 200 has been created out of nothing.</li>
     * </ul>
     */
    private Txn resolveCredit(Txn txn) {
        BankCallResult check = askWithRetries(payeeBank(txn), "STATUS_CHECK",
                () -> banks.statusCheckAndFence(payeeBank(txn), txn.txnId(), OpType.CREDIT),
                r -> r.outcome() != UNKNOWN);
        return switch (check.outcome()) {
            case SUCCESS -> move(txn, TxnState.SUCCESS, "CREDIT_CONFIRMED_BY_STATUS");
            case DECLINED -> move(txn, TxnState.REVERSAL_SENT, "CREDIT_DECLINED:" + check.detail());
            case UNKNOWN -> {
                log.warn("Txn {}: credit still unknown after {} status checks ({}). DEEMED_APPROVED, not reversing.",
                        txn.txnId(), retrier.maxAttempts(), check.detail());
                yield move(txn, TxnState.DEEMED_APPROVED, "CREDIT_STATUS_UNKNOWN: " + check.detail());
            }
        };
    }

    /**
     * DEEMED_APPROVED, checked again later (the payee's bank may be reachable now). Same question
     * as before, with fencing, but now every answer is safe to act on:
     * <ul>
     *   <li>the credit happened -> SUCCESS</li>
     *   <li>it never happened, and the fence now guarantees it never will -> REVERSAL_SENT. Refunding
     *       Ravi is safe at this point: Anu cannot also receive the money.</li>
     *   <li>still no answer -> stay parked. Still never reverse on a guess.</li>
     * </ul>
     */
    private Txn recheckDeemed(Txn txn) {
        BankCallResult check = askWithRetries(payeeBank(txn), "STATUS_CHECK",
                () -> banks.statusCheckAndFence(payeeBank(txn), txn.txnId(), OpType.CREDIT),
                r -> r.outcome() != UNKNOWN);
        return switch (check.outcome()) {
            case SUCCESS -> move(txn, TxnState.SUCCESS, "CREDIT_CONFIRMED_BY_STATUS");
            case DECLINED -> move(txn, TxnState.REVERSAL_SENT, "CREDIT_DECLINED:" + check.detail());
            case UNKNOWN -> {
                log.info("Txn {}: still DEEMED_APPROVED, payee bank still not answering ({}). Will check again later.",
                        txn.txnId(), check.detail());
                yield txn;                                    // no change; the job's lease sets the next check
            }
        };
    }

    /**
     * Keep asking the payer's bank to reverse until it confirms. /reverse is idempotent, so sending
     * it again never refunds twice: a repeat just gets the saved answer back.
     */
    private Txn resolveReversal(Txn txn) {
        BankCallResult reversal = askWithRetries(payerBank(txn), "REVERSE",
                () -> banks.reverse(payerBank(txn), new ReverseRequest(txn.txnId())),
                this::isFinalReversal);
        if (isFinalReversal(reversal)) {
            return finishReversal(txn, reversal);
        }
        log.error("Txn {}: reversal NOT confirmed after {} attempts ({}). Will retry later. Payer's money may not be back.",
                txn.txnId(), retrier.maxAttempts(), reversal.detail());
        return txns.note(txn.txnId(), txn.version(), "REVERSAL_UNCONFIRMED: " + reversal.detail());
    }

    /** A reversal answer we can act on: done (SUCCESS / NO_DEBIT), or "there was no debit". */
    private boolean isFinalReversal(BankCallResult r) {
        return r.outcome() == SUCCESS
                || (r.outcome() == DECLINED && NOTHING_TO_REVERSE.contains(r.declineReason()));
    }

    private Txn finishReversal(Txn txn, BankCallResult reversal) {
        if (reversal.outcome() == SUCCESS) {
            return move(txn, TxnState.REVERSED, reversal.isNoDebit() ? "REVERSAL_OK:NO_DEBIT" : "REVERSAL_OK");
        }
        // The bank says the debit itself was never successful, so the payer never paid.
        log.warn("Txn {}: reversal declined ({}): no debit to undo. Marking FAILED.", txn.txnId(), reversal.detail());
        return move(txn, TxnState.FAILED, "REVERSAL_DECLINED:" + reversal.detail());
    }

    // =========================================================================================

    /** One state change, guarded by the version we last saw. */
    private Txn move(Txn txn, TxnState to, String reason) {
        return txns.transition(txn.txnId(), txn.version(), to, reason);
    }

    private BankRoute payerBank(Txn txn) {
        return router.route(Vpa.parse(txn.payerVpa())).orElseThrow();
    }

    private BankRoute payeeBank(Txn txn) {
        return router.route(Vpa.parse(txn.payeeVpa())).orElseThrow();
    }
}

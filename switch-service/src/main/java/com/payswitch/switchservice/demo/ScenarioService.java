package com.payswitch.switchservice.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.payswitch.common.pay.ReqPay;
import com.payswitch.common.pay.RespPay;
import com.payswitch.switchservice.domain.Txn;
import com.payswitch.switchservice.domain.TxnState;
import com.payswitch.switchservice.service.PaymentOrchestrator;
import com.payswitch.switchservice.service.TransactionService;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * The demo's five scenarios. Each one sets up the banks (using their chaos endpoints) so ONE payment
 * meets one specific failure, then makes that payment through the normal flow. Nothing here takes a
 * shortcut: the recovery you see is the same code that runs under load.
 *
 * <p>Ravi (ravi@banka) pays Anu's shop (anu@bankb) in every scenario except insufficient-funds.
 * Only exists with switch.demo.enabled=true, and needs the banks' "chaos" profile.
 *
 * <p>Phase 13, safe for a public demo: only one scenario runs at a time ({@link DemoLock}; the rest
 * get "busy, try again"), and every scenario takes back the chaos it set up, in a finally block,
 * whether its payment used it or not.
 */
@Service
@ConditionalOnProperty(name = "switch.demo.enabled", havingValue = "true")
public class ScenarioService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioService.class);

    static final String RAVI = "ravi@banka";
    static final String ANU = "anu@bankb";
    static final String MEENA = "meena@banka";
    static final String KIRAN = "kiran@bankb";

    /** What happened, in words the demo UI can show. */
    public record Result(String scenario, String txnId, String state, String reason, String explanation) {
    }

    static final Set<String> NAMES = Set.of("happy", "insufficient-funds", "debit-reply-lost",
            "credit-unknown-deemed", "switch-crash");

    private final PaymentOrchestrator orchestrator;
    private final TransactionService txns;
    private final DemoCrash crash;
    private final DemoBankAdmin banks;
    private final DemoLock lock;

    public ScenarioService(PaymentOrchestrator orchestrator, TransactionService txns, DemoCrash crash,
                           DemoBankAdmin banks, DemoLock lock) {
        this.orchestrator = orchestrator;
        this.txns = txns;
        this.crash = crash;
        this.banks = banks;
        this.lock = lock;
    }

    /**
     * @throws IllegalArgumentException   unknown name (checked BEFORE taking the lock)
     * @throws DemoLock.DemoBusyException another scenario is running
     */
    public Result run(String name) {
        if (!NAMES.contains(name)) {
            throw new IllegalArgumentException("unknown scenario " + name + "; try happy, "
                    + "insufficient-funds, debit-reply-lost, credit-unknown-deemed or switch-crash");
        }
        lock.acquire(name);
        if (name.equals("switch-crash")) {
            return switchCrash();                         // releases the lock itself, from its own thread
        }
        try {
            return switch (name) {
                case "happy" -> happy();
                case "insufficient-funds" -> insufficientFunds();
                case "debit-reply-lost" -> debitReplyLost();
                default -> creditUnknownDeemed();
            };
        } finally {
            lock.release();
        }
    }

    /** Nothing goes wrong: debit, credit, SUCCESS. */
    Result happy() {
        RespPay r = pay(RAVI, ANU, 200_00);
        return result("happy", r, "Debited at Bank A, credited at Bank B. Four state changes, no surprises.");
    }

    /** Meena (Rs 2,000) tries to pay Rs 1,00,000: Bank A declines, nothing moves. */
    Result insufficientFunds() {
        RespPay r = pay(MEENA, KIRAN, 1_00_000_00);
        return result("insufficient-funds", r, "Bank A declined the debit (INSUFFICIENT_FUNDS). No money moved, "
                + "so the payment is simply FAILED: there is nothing to reverse.");
    }

    /**
     * Bank A debits Ravi but its reply never arrives. The switch must NOT call that a failure: it asks
     * Bank A (status check), learns the debit happened, and carries on to the credit.
     */
    Result debitReplyLost() {
        banks.armOnce("BANKA", "DEBIT", "drop_response");
        RespPay r;
        try {
            r = pay(RAVI, ANU, 50_00);
        } finally {
            banks.disarmOnce("BANKA", "DEBIT");             // normally already used; if not, gone now
        }
        return result("debit-reply-lost", r, "Bank A debited Ravi but the reply was lost (timeout after 5 s). "
                + "A status check found the debit (DEBIT_CONFIRMED_BY_STATUS), so the switch went on to credit Anu.");
    }

    /**
     * Bank B credits Anu, the reply is lost, AND Bank B's status checks keep failing. The switch cannot
     * know whether Anu got the money, so it parks the payment in DEEMED_APPROVED and never reverses on
     * a guess. Recon later reads Bank B's books, finds the credit, and finishes it as SUCCESS.
     */
    Result creditUnknownDeemed() {
        JsonNode before = banks.chaos("BANKB");
        ObjectNode failingChecks = before.deepCopy();
        failingChecks.put("statusErrorRate", 1.0);
        String txnId = newTxnId();
        try {
            banks.setChaos("BANKB", failingChecks);           // first: this also clears one-shot rules
            banks.armOnce("BANKB", "CREDIT", "drop_response");
            pay(new ReqPay(txnId, RAVI, ANU, 75_00));
            Txn txn = waitUntilNoLongerInFlight(txnId, Duration.ofSeconds(40));
            return new Result("credit-unknown-deemed", txnId, txn.state().name(), lastReason(txnId),
                    "Bank B credited Anu but the reply was lost, and every status check failed. The switch cannot "
                            + "know if Anu was paid, so it does NOT refund Ravi: DEEMED_APPROVED. Recon runs by itself "
                            + "every minute: within about two minutes it finds the credit in Bank B's books and the "
                            + "payment turns SUCCESS in the monitor. (Admins can press Run recon to do it now.)");
        } finally {
            banks.setChaos("BANKB", before);                  // status checks work again; the one-shot rule is gone
        }
    }

    /**
     * Arms the crash, answers 202 straight away, then pays from a background thread. The switch dies
     * after Bank A has debited Ravi but before it saves DEBITED; Docker restarts it, and startup
     * recovery asks Bank A, finds the debit, and finishes the payment.
     */
    Result switchCrash() {
        String txnId = newTxnId();
        try {
            crash.arm(txnId);                                 // only THIS payment can trigger it
            Thread.ofVirtual().name("demo-crash-payment").start(() -> {
                try {
                    Thread.sleep(500);                        // let this HTTP answer reach the browser first
                    pay(new ReqPay(txnId, RAVI, ANU, 100_00));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    log.warn("switch-crash scenario payment failed: {}", e.toString());
                } finally {
                    // Normally never reached: the process is gone. Reached if the payment got no debit
                    // (e.g. Ravi is out of money): stand down, and let the next scenario run.
                    crash.disarm(txnId);
                    lock.release();
                }
            });
        } catch (RuntimeException e) {
            crash.disarm(txnId);
            lock.release();
            throw e;
        }
        return new Result("switch-crash", txnId, "INITIATED", null, "The switch will die right after Bank A "
                + "debits Ravi, before it can save DEBITED. Watch the monitor reconnect when Docker restarts it (~10 s). "
                + "Ravi's money shows as in transit until the resolver picks the payment up (stuck-after, ~10 s more), "
                + "asks Bank A, finds the debit (DEBIT_CONFIRMED_BY_STATUS) and finishes it.");
    }

    // ---- helpers -----------------------------------------------------------------------------

    private RespPay pay(String payer, String payee, long paise) {
        return pay(new ReqPay(newTxnId(), payer, payee, paise));
    }

    private RespPay pay(ReqPay request) {
        log.info("Scenario payment {}: {} paise", request.txnId(), request.amount());
        return orchestrator.pay(request);
    }

    private Result result(String scenario, RespPay r, String explanation) {
        return new Result(scenario, r.txnId(), txns.get(r.txnId()).state().name(), r.reason(), explanation);
    }

    private String lastReason(String txnId) {
        var history = txns.history(txnId);
        return history.isEmpty() ? null : history.get(history.size() - 1).reason();
    }

    /** States that mean "still being worked on" (the resolver may still be running its status checks). */
    private static final Set<TxnState> IN_FLIGHT = EnumSet.of(TxnState.INITIATED, TxnState.DEBIT_SENT,
            TxnState.DEBITED, TxnState.CREDIT_SENT, TxnState.REVERSAL_SENT);

    /** Polls until the payment is final or DEEMED_APPROVED (or the timeout passes). */
    private Txn waitUntilNoLongerInFlight(String txnId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (true) {
            Txn txn = txns.get(txnId);
            if (!IN_FLIGHT.contains(txn.state()) || Instant.now().isAfter(deadline)) {
                return txn;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return txn;
            }
        }
    }

    private static String newTxnId() {
        return "TXN" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    /** The banks are not set up for scenarios (e.g. no chaos profile). Answered 409. */
    public static class ScenarioUnavailableException extends RuntimeException {
        public ScenarioUnavailableException(String message) {
            super(message);
        }
    }
}

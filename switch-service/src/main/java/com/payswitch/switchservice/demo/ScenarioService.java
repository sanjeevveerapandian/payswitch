package com.payswitch.switchservice.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.payswitch.common.pay.ReqPay;
import com.payswitch.common.pay.RespPay;
import com.payswitch.switchservice.domain.Txn;
import com.payswitch.switchservice.domain.TxnState;
import com.payswitch.switchservice.routing.BankRoute;
import com.payswitch.switchservice.routing.BankRouter;
import com.payswitch.switchservice.routing.SwitchProperties;
import com.payswitch.switchservice.service.PaymentOrchestrator;
import com.payswitch.switchservice.service.TransactionService;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The demo's five scenarios. Each one sets up the banks (using their chaos endpoints) so ONE payment
 * meets one specific failure, then makes that payment through the normal flow. Nothing here takes a
 * shortcut: the recovery you see is the same code that runs under load.
 *
 * <p>Ravi (ravi@banka) pays Anu's shop (anu@bankb) in every scenario except insufficient-funds.
 * Only exists with switch.demo.enabled=true, and needs the banks' "chaos" profile.
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

    private final PaymentOrchestrator orchestrator;
    private final TransactionService txns;
    private final BankRouter router;
    private final DemoCrash crash;
    private final RestClient http;

    public ScenarioService(PaymentOrchestrator orchestrator, TransactionService txns, BankRouter router,
                           DemoCrash crash, RestClient.Builder builder, SwitchProperties properties) {
        this.orchestrator = orchestrator;
        this.txns = txns;
        this.router = router;
        this.crash = crash;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.http().connectTimeout());
        factory.setReadTimeout(properties.http().readTimeout());
        this.http = builder.requestFactory(factory).build();
    }

    public Result run(String name) {
        return switch (name) {
            case "happy" -> happy();
            case "insufficient-funds" -> insufficientFunds();
            case "debit-reply-lost" -> debitReplyLost();
            case "credit-unknown-deemed" -> creditUnknownDeemed();
            case "switch-crash" -> switchCrash();
            default -> throw new IllegalArgumentException("unknown scenario " + name + "; try happy, "
                    + "insufficient-funds, debit-reply-lost, credit-unknown-deemed or switch-crash");
        };
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
        armOnce("BANKA", "DEBIT", "drop_response");
        RespPay r = pay(RAVI, ANU, 50_00);
        return result("debit-reply-lost", r, "Bank A debited Ravi but the reply was lost (timeout after 5 s). "
                + "A status check found the debit (DEBIT_CONFIRMED_BY_STATUS), so the switch went on to credit Anu.");
    }

    /**
     * Bank B credits Anu, the reply is lost, AND Bank B's status checks keep failing. The switch cannot
     * know whether Anu got the money, so it parks the payment in DEEMED_APPROVED and never reverses on
     * a guess. Recon later reads Bank B's books, finds the credit, and finishes it as SUCCESS.
     */
    Result creditUnknownDeemed() {
        JsonNode before = http.get().uri(bank("BANKB").url() + "/admin/chaos").retrieve().body(JsonNode.class);
        if (before == null) {
            throw new ScenarioUnavailableException("Bank B did not report its chaos settings");
        }
        ObjectNode failingChecks = before.deepCopy();
        failingChecks.put("statusErrorRate", 1.0);
        String txnId = newTxnId();
        try {
            setChaos("BANKB", failingChecks);                 // first: this also clears one-shot rules
            armOnce("BANKB", "CREDIT", "drop_response");
            pay(new ReqPay(txnId, RAVI, ANU, 75_00));
            Txn txn = waitUntilNoLongerInFlight(txnId, Duration.ofSeconds(40));
            return new Result("credit-unknown-deemed", txnId, txn.state().name(), lastReason(txnId),
                    "Bank B credited Anu but the reply was lost, and every status check failed. The switch cannot "
                            + "know if Anu was paid, so it does NOT refund Ravi: DEEMED_APPROVED. Wait ~10 s, then "
                            + "press Run recon: recon finds the credit in Bank B's books and finishes it as SUCCESS.");
        } finally {
            setChaos("BANKB", before);                        // Bank B's status checks work again
        }
    }

    /**
     * Arms the crash, answers 202 straight away, then pays from a background thread. The switch dies
     * after Bank A has debited Ravi but before it saves DEBITED; Docker restarts it, and startup
     * recovery asks Bank A, finds the debit, and finishes the payment.
     */
    Result switchCrash() {
        crash.arm();
        String txnId = newTxnId();
        Thread.ofVirtual().name("demo-crash-payment").start(() -> {
            try {
                Thread.sleep(500);                            // let this HTTP answer reach the browser first
                pay(new ReqPay(txnId, RAVI, ANU, 100_00));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.warn("switch-crash scenario payment failed: {}", e.toString());
            }
        });
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

    private void armOnce(String bankCode, String op, String mode) {
        try {
            http.post().uri(bank(bankCode).url() + "/admin/chaos/once").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("op", op, "mode", mode)).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw new ScenarioUnavailableException(bankCode + " refused to arm chaos (is its 'chaos' profile on?): "
                    + e.getMessage());
        }
    }

    private void setChaos(String bankCode, JsonNode settings) {
        http.post().uri(bank(bankCode).url() + "/admin/chaos").contentType(MediaType.APPLICATION_JSON)
                .body(settings).retrieve().toBodilessEntity();
    }

    private BankRoute bank(String code) {
        return router.byCode(code).orElseThrow(() -> new ScenarioUnavailableException("no bank " + code));
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

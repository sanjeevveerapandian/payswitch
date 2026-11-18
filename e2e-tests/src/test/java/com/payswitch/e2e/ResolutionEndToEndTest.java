package com.payswitch.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.payswitch.bank.chaos.ChaosDecision;
import com.payswitch.bank.chaos.ChaosEngine;
import com.payswitch.bank.chaos.ChaosSettings;
import com.payswitch.common.bank.BalanceResponse;
import com.payswitch.common.bank.BankResponse;
import com.payswitch.common.bank.BankResult;
import com.payswitch.common.bank.DeclineReason;
import com.payswitch.common.bank.StatusResponse;
import com.payswitch.common.bank.TxnStatus;
import com.payswitch.common.pay.PayState;
import com.payswitch.common.pay.RespPay;
import com.payswitch.common.pay.TxnDetails;
import com.payswitch.common.pay.TxnEventView;
import com.payswitch.switchservice.domain.Txn;
import com.payswitch.switchservice.domain.TxnState;
import com.payswitch.switchservice.service.PaymentFlow;
import com.payswitch.switchservice.service.TransactionService;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Phase 6: every UNKNOWN gets resolved. Real banks with chaos, the switch with resolution on.
 * Every test ends by checking where Ravi's Rs 200 is, and (after each test) that the total money
 * across all banks is unchanged.
 *
 * <p>Ravi (Bank A) pays Rs 200 to Anu's shop (Bank B).
 */
class ResolutionEndToEndTest {

    private static final long RS_200 = 20_000;
    private static final PaymentSystem system = PaymentSystem.shared();
    private static final RestClient http = RestClient.create();

    private Money before;
    private long raviBefore;
    private long anuBefore;

    @BeforeEach
    void rememberWhereTheMoneyIs() {
        system.chaosOff();
        before = Money.snapshot(system);
        raviBefore = ravi();
        anuBefore = anu();
    }

    @AfterEach
    void moneyIsConserved() {
        system.chaosOff();
        assertEquals(before.total(), Money.snapshot(system).total(), "money was created or destroyed");
        system.auditAllBanks();
    }

    // ---- debit UNKNOWN ------------------------------------------------------------------

    @Test
    void debitResponseLost_statusFindsIt_paymentSucceeds() {
        system.chaos(system.bankAUrl(), Map.of("dropResponseRate", 1.0));

        RespPay resp = pay("anu@bankb");

        assertEquals(PayState.SUCCESS, resp.state());
        assertTrue(reasons(resp.txnId()).containsAll(List.of("DEBIT_UNKNOWN: timeout", "DEBIT_CONFIRMED_BY_STATUS")));
        // Where is the Rs 200? With Anu.
        assertMovedRaviToAnu();
    }

    @Test
    void debitFailedWith500_statusFencesIt_failed_andALateDebitIsRefused() {
        system.chaos(system.bankAUrl(), Map.of("errorRate", 1.0));

        RespPay resp = pay("anu@bankb");

        assertEquals(new RespPay(resp.txnId(), PayState.FAILED, "DEBIT_DECLINED:FENCED"), resp);

        // The original debit turns up late (stuck in some network buffer). It must not run.
        system.chaosOff();
        BankResponse late = debitDirect(resp.txnId());
        assertEquals(BankResult.DECLINED, late.result());
        assertEquals(DeclineReason.FENCED, late.reason());

        // Where is the Rs 200? Still with Ravi. Nothing moved anywhere.
        assertEquals(raviBefore, ravi());
        assertEquals(before, Money.snapshot(system));
    }

    /** The debit DID happen, the status check never answers: reverse, and Ravi gets it back. */
    @Test
    void debitResponseLost_statusDown_reversed_raviRefunded() {
        long seed = seedGiving(new ChaosSettings(0, 0, 0, 0.5, null), ChaosDecision.DROP_RESPONSE, ChaosDecision.PROCEED);
        system.chaos(system.bankAUrl(), Map.of("dropResponseRate", 0.5, "seed", seed, "statusErrorRate", 1.0));

        RespPay resp = pay("anu@bankb");

        assertEquals(new RespPay(resp.txnId(), PayState.REVERSED, "REVERSAL_OK"), resp);
        assertEquals(TxnStatus.SUCCESS, bankStatus(system.bankAUrl(), resp.txnId(), "DEBIT"));     // it happened...
        assertEquals(TxnStatus.SUCCESS, bankStatus(system.bankAUrl(), resp.txnId(), "REVERSE"));   // ...and was undone
        // Where is the Rs 200? Back with Ravi.
        assertEquals(raviBefore, ravi());
        assertEquals(before, Money.snapshot(system));
    }

    /** The debit did NOT happen, the status check never answers: the reverse finds no debit. */
    @Test
    void debitFailedWith500_statusDown_reversedWithNoDebit_andALateDebitIsRefused() {
        long seed = seedGiving(new ChaosSettings(0, 0.5, 0, 0, null), ChaosDecision.ERROR, ChaosDecision.PROCEED);
        system.chaos(system.bankAUrl(), Map.of("errorRate", 0.5, "seed", seed, "statusErrorRate", 1.0));

        RespPay resp = pay("anu@bankb");

        assertEquals(new RespPay(resp.txnId(), PayState.REVERSED, "REVERSAL_OK:NO_DEBIT"), resp);
        system.chaosOff();
        assertEquals(DeclineReason.ALREADY_REVERSED, debitDirect(resp.txnId()).reason());
        // Where is the Rs 200? It never left Ravi.
        assertEquals(raviBefore, ravi());
        assertEquals(before, Money.snapshot(system));
    }

    // ---- credit UNKNOWN -----------------------------------------------------------------

    @Test
    void creditResponseLost_statusFindsIt_paymentSucceeds() {
        system.chaos(system.bankBUrl(), Map.of("dropResponseRate", 1.0));

        RespPay resp = pay("anu@bankb");

        assertEquals(new RespPay(resp.txnId(), PayState.SUCCESS, "CREDIT_CONFIRMED_BY_STATUS"), resp);
        assertMovedRaviToAnu();
    }

    @Test
    void creditFailedWith500_statusFencesIt_reversed_andALateCreditIsRefused() {
        system.chaos(system.bankBUrl(), Map.of("errorRate", 1.0));

        RespPay resp = pay("anu@bankb");

        assertEquals(new RespPay(resp.txnId(), PayState.REVERSED, "REVERSAL_OK"), resp);
        assertTrue(reasons(resp.txnId()).contains("CREDIT_DECLINED:FENCED"));

        system.chaosOff();
        BankResponse late = creditDirect(resp.txnId());
        assertEquals(DeclineReason.FENCED, late.reason());

        // Where is the Rs 200? Back with Ravi. Anu never got it, and now never will.
        assertEquals(raviBefore, ravi());
        assertEquals(anuBefore, anu());
        assertEquals(before, Money.snapshot(system));
    }

    /** Anu's bank is unreachable for both credit and status: DEEMED_APPROVED, and NO reversal. */
    @Test
    void creditFailedWith500_statusDown_deemedApproved_debitNotReversed() {
        system.chaos(system.bankBUrl(), Map.of("errorRate", 1.0, "statusErrorRate", 1.0));

        RespPay resp = pay("anu@bankb");

        assertEquals(PayState.PENDING, resp.state());
        assertEquals("DEEMED_APPROVED", switchState(resp.txnId()));
        assertEquals(TxnStatus.NOT_FOUND, bankStatus(system.bankAUrl(), resp.txnId(), "REVERSE"));
        // Where is the Rs 200? In transit: taken from Ravi, held in Bank A's settlement account.
        Money after = Money.snapshot(system);
        assertEquals(raviBefore - RS_200, ravi());
        assertEquals(anuBefore, anu());
        assertEquals(+RS_200, after.settlementA() - before.settlementA());
    }

    /**
     * The same situation, but this time the credit DID reach Anu. The switch cannot tell the two
     * apart, and this is why it must not reverse: had it refunded Ravi here, Ravi would have his
     * Rs 200 back AND Anu would keep hers. Rs 200 created out of nothing.
     */
    @Test
    void creditResponseLost_statusDown_deemedApproved_andAnuReallyHasTheMoney() {
        system.chaos(system.bankBUrl(), Map.of("dropResponseRate", 1.0, "statusErrorRate", 1.0));

        RespPay resp = pay("anu@bankb");

        assertEquals("DEEMED_APPROVED", switchState(resp.txnId()));
        // Where is the Rs 200? With Anu. The payment really did go through.
        assertMovedRaviToAnu();
        assertEquals(TxnStatus.NOT_FOUND, bankStatus(system.bankAUrl(), resp.txnId(), "REVERSE"));
    }

    // ---- DEEMED_APPROVED, checked again later (review finding #3) ------------------------

    /** Anu's bank was down; later it is back and has no record of the credit. Ravi is refunded. */
    @Test
    void deemedApprovedLaterFound_creditNeverHappened_raviRefunded() {
        system.chaos(system.bankBUrl(), Map.of("errorRate", 1.0, "statusErrorRate", 1.0));
        RespPay resp = pay("anu@bankb");
        assertEquals("DEEMED_APPROVED", switchState(resp.txnId()));
        assertEquals(raviBefore - RS_200, ravi());                        // money in transit

        system.chaosOff();                                                // Bank B is back
        system.switchBean(PaymentFlow.class).resolve(resp.txnId());       // what the job does later

        assertEquals("REVERSED", switchState(resp.txnId()));
        // Where is the Rs 200? Back with Ravi; Anu never got it, and the fence means she never will.
        assertEquals(raviBefore, ravi());
        assertEquals(anuBefore, anu());
        assertEquals(DeclineReason.FENCED, creditDirect(resp.txnId()).reason());
        assertEquals(before, Money.snapshot(system));
    }

    /** Same, but the credit DID reach Anu: the recheck confirms it and nothing is reversed. */
    @Test
    void deemedApprovedLaterFound_creditHappened_success() {
        system.chaos(system.bankBUrl(), Map.of("dropResponseRate", 1.0, "statusErrorRate", 1.0));
        RespPay resp = pay("anu@bankb");
        assertEquals("DEEMED_APPROVED", switchState(resp.txnId()));

        system.chaosOff();
        system.switchBean(PaymentFlow.class).resolve(resp.txnId());

        assertEquals("SUCCESS", switchState(resp.txnId()));
        assertEquals(TxnStatus.NOT_FOUND, bankStatus(system.bankAUrl(), resp.txnId(), "REVERSE"));
        // Where is the Rs 200? With Anu, once.
        assertMovedRaviToAnu();
    }

    // ---- reversal -----------------------------------------------------------------------

    /**
     * Bank B has no "ghost", so the switch must reverse. Bank A's dice: debit fine, then the first
     * two reverse replies are lost (the reversal IS done the first time), the third gets through.
     * Three /reverse requests, money returned exactly once.
     */
    @Test
    void reverseSentThreeTimes_moneyReturnedOnce() {
        long seed = seedGiving(new ChaosSettings(0, 0, 0, 0.5, null),
                ChaosDecision.PROCEED, ChaosDecision.DROP_RESPONSE, ChaosDecision.DROP_RESPONSE, ChaosDecision.PROCEED);
        system.chaos(system.bankAUrl(), Map.of("dropResponseRate", 0.5, "seed", seed));

        RespPay resp = pay("ghost@bankb");

        assertEquals(new RespPay(resp.txnId(), PayState.REVERSED, "REVERSAL_OK"), resp);
        // Bank A booked one debit and ONE reversal, not three.
        assertEquals(2, system.bankADb().sql("SELECT COUNT(*) FROM journal_entries WHERE txn_id = :t")
                .param("t", resp.txnId()).query(Integer.class).single());
        // Where is the Rs 200? Back with Ravi, once.
        assertEquals(raviBefore, ravi());
        assertEquals(before, Money.snapshot(system));
    }

    // ---- crash recovery -----------------------------------------------------------------

    /**
     * Two payments the switch saved as DEBIT_SENT just before "crashing":
     * <ul>
     *   <li>A: the debit request reached Bank A and Ravi was debited.</li>
     *   <li>B: the debit request never left the switch.</li>
     * </ul>
     * Restart the switch. Its startup recovery must finish A (credit Anu) and close B (fence it).
     */
    @Test
    void crashRightAfterSendingTheDebit_restartFinishesEveryPayment() {
        TransactionService txns = system.switchBean(TransactionService.class);
        String reached = "TXN-" + UUID.randomUUID();
        String neverSent = "TXN-" + UUID.randomUUID();
        for (String id : List.of(reached, neverSent)) {
            Txn txn = txns.create(id, "ravi@banka", "anu@bankb", RS_200);
            txns.transition(id, txn.version(), TxnState.DEBIT_SENT, "DEBIT_REQUESTED:BANKA");
        }
        assertEquals(BankResult.SUCCESS, debitDirect(reached).result());   // the request that got through

        system.restartSwitch();                                            // crash + restart

        assertEquals("SUCCESS", switchState(reached));
        assertEquals("FAILED", switchState(neverSent));
        assertEquals("DEBIT_DECLINED:FENCED", reasons(neverSent).get(reasons(neverSent).size() - 1));
        // If B's debit request shows up after all, it is refused.
        assertEquals(DeclineReason.FENCED, debitDirect(neverSent).reason());
        // Where is the money? A's Rs 200 is with Anu; B's never left Ravi.
        assertMovedRaviToAnu();
        // And the restarted switch takes new payments normally.
        assertEquals(PayState.SUCCESS, pay("kiran@bankb").state());
    }

    // ---------------------------------------------------------------------------------------

    /** Ravi -Rs 200, Anu +Rs 200; Bank A owes the switch Rs 200, the switch owes Bank B Rs 200. */
    private void assertMovedRaviToAnu() {
        Money after = Money.snapshot(system);
        assertEquals(raviBefore - RS_200, ravi());
        assertEquals(anuBefore + RS_200, anu());
        assertEquals(+RS_200, after.settlementA() - before.settlementA());
        assertEquals(-RS_200, after.settlementB() - before.settlementB());
    }

    /** Finds a seed whose first rolls give the wanted decisions (one roll per bank operation). */
    private static long seedGiving(ChaosSettings rates, ChaosDecision... wanted) {
        for (long seed = 1; seed < 1_000_000; seed++) {
            Random dice = new Random(seed);
            boolean match = true;
            for (ChaosDecision w : wanted) {
                if (ChaosEngine.decide(rates, dice.nextDouble()) != w) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return seed;
            }
        }
        throw new IllegalStateException("no seed found");
    }

    private static RespPay pay(String payeeVpa) {
        return http.post().uri(system.pspUrl() + "/pay")
                .body(Map.of("payerVpa", "ravi@banka", "payeeVpa", payeeVpa, "amount", "200.00"))
                .retrieve().body(RespPay.class);
    }

    private static BankResponse debitDirect(String txnId) {
        return http.post().uri(system.bankAUrl() + "/debit")
                .body(Map.of("txnId", txnId, "vpa", "ravi@banka", "amount", RS_200))
                .retrieve().body(BankResponse.class);
    }

    private static BankResponse creditDirect(String txnId) {
        return http.post().uri(system.bankBUrl() + "/credit")
                .body(Map.of("txnId", txnId, "vpa", "anu@bankb", "amount", RS_200))
                .retrieve().body(BankResponse.class);
    }

    private static long ravi() {
        return http.get().uri(system.bankAUrl() + "/accounts/ravi@banka/balance")
                .retrieve().body(BalanceResponse.class).balance();
    }

    private static long anu() {
        return http.get().uri(system.bankBUrl() + "/accounts/anu@bankb/balance")
                .retrieve().body(BalanceResponse.class).balance();
    }

    private static TxnStatus bankStatus(String bankUrl, String txnId, String op) {
        return http.get().uri(bankUrl + "/status/" + txnId + "?op=" + op)
                .retrieve().body(StatusResponse.class).status();
    }

    private static TxnDetails details(String txnId) {
        return http.get().uri(system.switchUrl() + "/txns/" + txnId).retrieve().body(TxnDetails.class);
    }

    private static String switchState(String txnId) {
        return details(txnId).state();
    }

    private static List<String> reasons(String txnId) {
        return details(txnId).events().stream().map(TxnEventView::reason).toList();
    }
}

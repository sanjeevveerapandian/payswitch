package com.payswitch.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.payswitch.bank.chaos.ChaosDecision;
import com.payswitch.bank.chaos.ChaosEngine;
import com.payswitch.bank.chaos.ChaosSettings;
import com.payswitch.common.bank.BalanceResponse;
import com.payswitch.common.bank.StatusResponse;
import com.payswitch.common.bank.TxnStatus;
import com.payswitch.common.pay.PayState;
import com.payswitch.common.pay.RespPay;
import com.payswitch.common.pay.TxnDetails;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Unreliable banks, real payments. Each test checks TWO views of the same payment:
 * <ul>
 *   <li>what the switch believes (its state and the reason in txn_events), and</li>
 *   <li>what really happened (balances and each bank's own /status).</li>
 * </ul>
 * Where they differ is exactly the problem Phase 6 has to solve.
 *
 * <p>Ravi (Bank A) pays Rs 200 to Anu's shop (Bank B) in every test.
 *
 * <p>Runs against the "raw" switch: resolution off, first attempt only. These tests pin down what
 * ONE attempt sees; ResolutionEndToEndTest shows how Phase 6 then finds out the truth.
 */
class ChaosEndToEndTest {

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
        raviBefore = balance(system.bankAUrl(), "ravi@banka");
        anuBefore = balance(system.bankBUrl(), "anu@bankb");
    }

    /**
     * Whatever chaos did, money was never created or destroyed. It may be stuck in transit (a
     * settlement account) because nothing recovers stuck payments yet, but the total holds.
     */
    @AfterEach
    void moneyIsConserved() {
        system.chaosOff();
        assertEquals(before.total(), Money.snapshot(system).total(), "money was created or destroyed");
        system.auditBothBanks();
    }

    // ---- chaos at the PAYER's bank (the debit) -----------------------------------------

    @Test
    void declineOnDebit_failedAndNothingMoved() {
        system.chaos(system.bankAUrl(), Map.of("declineRate", 1.0));

        RespPay resp = pay("ravi@banka", "anu@bankb");

        assertEquals(PayState.FAILED, resp.state());
        assertEquals("DEBIT_DECLINED:SIMULATED_DECLINE", resp.reason());
        assertEquals(before, Money.snapshot(system));
        assertEquals(TxnStatus.NOT_FOUND, bankStatus(system.bankAUrl(), resp.txnId(), "DEBIT"));
    }

    /**
     * The bank answered 500 and did NOTHING. The switch sees UNKNOWN.
     *
     * <p>Compare with the drop-response test below: there the bank DID take the money, and the
     * switch also sees UNKNOWN. The detail text differs ("HTTP 500" vs "timeout"), but neither
     * one tells the switch whether money moved: a real 500 can come AFTER a commit (a crash while
     * writing the reply), and a timeout can happen before the bank even started. From the
     * switch's side the two cases cannot be told apart. Only asking Bank A can settle it.
     */
    @Test
    void errorOnDebit_unknownAndRaviIsNotDebited() {
        system.chaos(system.bankAUrl(), Map.of("errorRate", 1.0));

        RespPay resp = pay("ravi@banka", "anu@bankb");

        // What the switch believes:
        assertEquals(PayState.PENDING, resp.state());
        assertEquals("DEBIT_SENT", switchState(resp.txnId()));
        assertEquals("DEBIT_UNKNOWN: HTTP 500", resp.reason());
        // What really happened:
        assertEquals(raviBefore, balance(system.bankAUrl(), "ravi@banka"));
        assertEquals(TxnStatus.NOT_FOUND, bankStatus(system.bankAUrl(), resp.txnId(), "DEBIT"));
        assertEquals(before, Money.snapshot(system));
    }

    /** THE key test: a timeout is not a failure. The switch sees UNKNOWN, yet Ravi WAS debited. */
    @Test
    void dropResponseOnDebit_unknownButRaviIsDebited() {
        system.chaos(system.bankAUrl(), Map.of("dropResponseRate", 1.0));

        RespPay resp = pay("ravi@banka", "anu@bankb");

        // What the switch believes: it does not know.
        assertEquals(PayState.PENDING, resp.state());
        assertEquals("DEBIT_SENT", switchState(resp.txnId()));
        assertEquals("DEBIT_UNKNOWN: timeout", resp.reason());
        // What really happened: Bank A took the money.
        assertEquals(raviBefore - RS_200, balance(system.bankAUrl(), "ravi@banka"));
        assertEquals(TxnStatus.SUCCESS, bankStatus(system.bankAUrl(), resp.txnId(), "DEBIT"));
        // Where the money is: in Bank A's settlement account, owed to the switch. Anu has nothing.
        Money after = Money.snapshot(system);
        assertEquals(-RS_200, after.customersA() - before.customersA());
        assertEquals(+RS_200, after.settlementA() - before.settlementA());
        assertEquals(anuBefore, balance(system.bankBUrl(), "anu@bankb"));
    }

    @Test
    void shortDelayOnDebit_justSlower() {
        system.chaos(system.bankAUrl(), Map.of("delayMs", 300));    // under the 1 s timeout

        RespPay resp = pay("ravi@banka", "anu@bankb");

        assertEquals(PayState.SUCCESS, resp.state());
        assertEquals(raviBefore - RS_200, balance(system.bankAUrl(), "ravi@banka"));
        assertEquals(anuBefore + RS_200, balance(system.bankBUrl(), "anu@bankb"));
    }

    /**
     * The bank is slow BEFORE it starts: the switch gives up at 1 s, the bank wakes at 1.5 s and
     * debits Ravi anyway. At the moment the switch answers, the money has not moved yet; a
     * moment later it has. "Timed out" said nothing about what would happen next.
     */
    @Test
    void longDelayOnDebit_unknownAndRaviIsDebitedLater() {
        system.chaos(system.bankAUrl(), Map.of("delayMs", 1_500));

        RespPay resp = pay("ravi@banka", "anu@bankb");

        assertEquals(PayState.PENDING, resp.state());
        assertEquals("DEBIT_UNKNOWN: timeout", resp.reason());
        eventually(Duration.ofSeconds(5),
                () -> balance(system.bankAUrl(), "ravi@banka") == raviBefore - RS_200);
        assertEquals(TxnStatus.SUCCESS, bankStatus(system.bankAUrl(), resp.txnId(), "DEBIT"));
        assertEquals("DEBIT_SENT", switchState(resp.txnId()));      // the switch still does not know
    }

    // ---- chaos at the PAYEE's bank (the credit) ----------------------------------------

    @Test
    void declineOnCredit_reversedAndRaviIsWhole() {
        system.chaos(system.bankBUrl(), Map.of("declineRate", 1.0));

        RespPay resp = pay("ravi@banka", "anu@bankb");

        assertEquals(PayState.REVERSED, resp.state());
        assertEquals("CREDIT_DECLINED:SIMULATED_DECLINE", reasons(resp.txnId()).get(4));
        assertEquals(raviBefore, balance(system.bankAUrl(), "ravi@banka"));
        assertEquals(before, Money.snapshot(system));
    }

    @Test
    void errorOnCredit_unknownAndMoneyIsInBankASettlement() {
        system.chaos(system.bankBUrl(), Map.of("errorRate", 1.0));

        RespPay resp = pay("ravi@banka", "anu@bankb");

        assertEquals(PayState.PENDING, resp.state());
        assertEquals("CREDIT_SENT", switchState(resp.txnId()));
        assertEquals("CREDIT_UNKNOWN: HTTP 500", resp.reason());
        // Ravi paid, Anu did not receive: the Rs 200 is in transit at Bank A.
        assertEquals(raviBefore - RS_200, balance(system.bankAUrl(), "ravi@banka"));
        assertEquals(anuBefore, balance(system.bankBUrl(), "anu@bankb"));
        assertEquals(TxnStatus.NOT_FOUND, bankStatus(system.bankBUrl(), resp.txnId(), "CREDIT"));
        Money after = Money.snapshot(system);
        assertEquals(+RS_200, after.settlementA() - before.settlementA());
        assertEquals(0, after.settlementB() - before.settlementB());
    }

    /** The payment really SUCCEEDED, yet the switch must say PENDING. Reversing now would pay twice. */
    @Test
    void dropResponseOnCredit_unknownButAnuIsCredited() {
        system.chaos(system.bankBUrl(), Map.of("dropResponseRate", 1.0));

        RespPay resp = pay("ravi@banka", "anu@bankb");

        // What the switch believes:
        assertEquals(PayState.PENDING, resp.state());
        assertEquals("CREDIT_SENT", switchState(resp.txnId()));
        assertEquals("CREDIT_UNKNOWN: timeout", resp.reason());
        // What really happened: the whole payment went through.
        assertEquals(raviBefore - RS_200, balance(system.bankAUrl(), "ravi@banka"));
        assertEquals(anuBefore + RS_200, balance(system.bankBUrl(), "anu@bankb"));
        assertEquals(TxnStatus.SUCCESS, bankStatus(system.bankBUrl(), resp.txnId(), "CREDIT"));
        assertEquals(TxnStatus.NOT_FOUND, bankStatus(system.bankAUrl(), resp.txnId(), "REVERSE"));
    }

    // ---- chaos on the REVERSAL ---------------------------------------------------------

    /**
     * Payee "ghost" does not exist, so Bank B declines and the switch must reverse at Bank A.
     * The seed makes Bank A's dice say PROCEED for the debit and ERROR for the reversal.
     */
    @Test
    void errorOnReversal_unknownAndRavisMoneyIsStillOut() {
        ChaosSettings rates = new ChaosSettings(0, 0.5, 0, 0, null);
        long seed = seedGiving(rates, ChaosDecision.PROCEED, ChaosDecision.ERROR);
        system.chaos(system.bankAUrl(), Map.of("errorRate", 0.5, "seed", seed));

        RespPay resp = pay("ravi@banka", "ghost@bankb");

        assertEquals(PayState.PENDING, resp.state());
        assertEquals("REVERSAL_SENT", switchState(resp.txnId()));
        assertEquals("REVERSAL_UNKNOWN: HTTP 500", resp.reason());
        assertEquals(raviBefore - RS_200, balance(system.bankAUrl(), "ravi@banka"));
        assertEquals(TxnStatus.NOT_FOUND, bankStatus(system.bankAUrl(), resp.txnId(), "REVERSE"));
        assertEquals(+RS_200, Money.snapshot(system).settlementA() - before.settlementA());
    }

    @Test
    void simulatedDeclineOnReversal_doesNotPretendRaviWasRefunded() {
        ChaosSettings rates = new ChaosSettings(0.5, 0, 0, 0, null);
        long seed = seedGiving(rates, ChaosDecision.PROCEED, ChaosDecision.DECLINE);
        system.chaos(system.bankAUrl(), Map.of("declineRate", 0.5, "seed", seed));

        RespPay resp = pay("ravi@banka", "ghost@bankb");

        // Not FAILED: "no" without "there was no debit" proves nothing about Ravi's money.
        assertEquals(PayState.PENDING, resp.state());
        assertEquals("REVERSAL_SENT", switchState(resp.txnId()));
        assertEquals("REVERSAL_DECLINED: SIMULATED_DECLINE", resp.reason());
        assertEquals(raviBefore - RS_200, balance(system.bankAUrl(), "ravi@banka"));
    }

    // ---------------------------------------------------------------------------------------

    /**
     * Finds a seed whose first two rolls give the wanted decisions. The bank rolls its dice
     * exactly once per /debit, /credit or /reverse call, so the payment's debit gets the first
     * roll and its reversal the second.
     */
    private static long seedGiving(ChaosSettings rates, ChaosDecision first, ChaosDecision second) {
        for (long seed = 1; seed < 100_000; seed++) {
            Random dice = new Random(seed);
            if (ChaosEngine.decide(rates, dice.nextDouble()) == first
                    && ChaosEngine.decide(rates, dice.nextDouble()) == second) {
                return seed;
            }
        }
        throw new IllegalStateException("no seed found");
    }

    private static RespPay pay(String payerVpa, String payeeVpa) {
        return http.post().uri(system.rawPspUrl() + "/pay")
                .body(Map.of("payerVpa", payerVpa, "payeeVpa", payeeVpa, "amount", "200.00"))
                .retrieve()
                .body(RespPay.class);
    }

    private static long balance(String bankUrl, String vpa) {
        return http.get().uri(bankUrl + "/accounts/" + vpa + "/balance")
                .retrieve().body(BalanceResponse.class).balance();
    }

    private static TxnStatus bankStatus(String bankUrl, String txnId, String op) {
        return http.get().uri(bankUrl + "/status/" + txnId + "?op=" + op)
                .retrieve().body(StatusResponse.class).status();
    }

    private static String switchState(String txnId) {
        return details(txnId).state();
    }

    private static List<String> reasons(String txnId) {
        return details(txnId).events().stream().map(e -> e.reason()).toList();
    }

    private static TxnDetails details(String txnId) {
        return http.get().uri(system.rawSwitchUrl() + "/txns/" + txnId).retrieve().body(TxnDetails.class);
    }

    private static void eventually(Duration within, java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + within.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            }
        }
        assertTrue(condition.getAsBoolean(), "condition not met within " + within);
    }
}

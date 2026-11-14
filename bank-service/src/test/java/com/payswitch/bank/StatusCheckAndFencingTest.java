package com.payswitch.bank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.payswitch.common.bank.BankResponse;
import com.payswitch.common.bank.BankResult;
import com.payswitch.common.bank.CheckResult;
import com.payswitch.common.bank.CreditRequest;
import com.payswitch.common.bank.DebitRequest;
import com.payswitch.common.bank.DeclineReason;
import com.payswitch.common.bank.OpType;
import com.payswitch.common.bank.ReverseRequest;
import com.payswitch.common.bank.StatusCheckRequest;
import com.payswitch.common.bank.StatusCheckResponse;
import com.payswitch.common.bank.TxnStatus;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/** POST /status-check, fencing tombstones, NO_DEBIT reversals, and the per-txnId lock. */
class StatusCheckAndFencingTest extends BankIntegrationTest {

    @Autowired
    private TestRestTemplate http;

    // ---- status-check -------------------------------------------------------------------

    @Test
    void aSavedAnswerIsReturnedExactlyAsItWasGiven() {
        String ravi = newCustomer(500_000);
        String ok = newTxnId();
        String poor = newTxnId();
        BankResponse okAnswer = bank.debit(new DebitRequest(ok, ravi, 20_000));
        BankResponse poorAnswer = bank.debit(new DebitRequest(poor, ravi, 999_999));

        StatusCheckResponse okCheck = bank.statusCheck(new StatusCheckRequest(ok, OpType.DEBIT, true));
        StatusCheckResponse poorCheck = bank.statusCheck(new StatusCheckRequest(poor, OpType.DEBIT, true));

        assertEquals(CheckResult.FOUND, okCheck.result());
        assertEquals(okAnswer, okCheck.saved());
        assertEquals(CheckResult.FOUND, poorCheck.result());
        assertEquals(DeclineReason.INSUFFICIENT_FUNDS, poorCheck.saved().reason());
        assertEquals(480_000, balance(ravi));                  // checking moved nothing
    }

    @Test
    void notFoundWithoutFenceWritesNothing() {
        String ravi = newCustomer(500_000);
        String txn = newTxnId();

        StatusCheckResponse check = bank.statusCheck(new StatusCheckRequest(txn, OpType.DEBIT, false));

        assertEquals(CheckResult.NOT_FOUND, check.result());
        assertNull(check.saved());
        // Nothing was promised, so the debit can still arrive and run normally.
        assertEquals(BankResult.SUCCESS, bank.debit(new DebitRequest(txn, ravi, 20_000)).result());
    }

    @Test
    void fencedDebitIsDeclinedForeverAfter() {
        String ravi = newCustomer(500_000);
        String txn = newTxnId();

        StatusCheckResponse check = bank.statusCheck(new StatusCheckRequest(txn, OpType.DEBIT, true));

        assertEquals(CheckResult.FENCED, check.result());
        assertEquals(DeclineReason.FENCED, check.saved().reason());

        // The original debit finally arrives (it was stuck somewhere in the network).
        BankResponse late = bank.debit(new DebitRequest(txn, ravi, 20_000));

        assertEquals(BankResult.DECLINED, late.result());
        assertEquals(DeclineReason.FENCED, late.reason());
        assertEquals(500_000, balance(ravi));
        assertEquals(0, journalEntriesFor(txn));
        // Asking again gives the same answer, and the read-only status agrees.
        assertEquals(CheckResult.FENCED, bank.statusCheck(new StatusCheckRequest(txn, OpType.DEBIT, true)).result());
        assertEquals(TxnStatus.FAILED, bank.status(txn, OpType.DEBIT).status());
    }

    @Test
    void fencedCreditIsDeclinedForeverAfter() {
        String anu = newCustomer(100_000);
        String txn = newTxnId();

        bank.statusCheck(new StatusCheckRequest(txn, OpType.CREDIT, true));
        BankResponse late = bank.credit(new CreditRequest(txn, anu, 20_000));

        assertEquals(DeclineReason.FENCED, late.reason());
        assertEquals(100_000, balance(anu));
    }

    @Test
    void fencingAReverseIsRefusedOverHttp() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        var response = http.postForEntity("/status-check", new HttpEntity<>("""
                {"txnId": "T1", "op": "REVERSE", "fence": true}""", headers), String.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("cannot be fenced"), response.getBody());
    }

    // ---- reverse ------------------------------------------------------------------------

    @Test
    void reverseSentThreeTimesReturnsTheMoneyOnce() {
        String ravi = newCustomer(500_000);
        String txn = newTxnId();
        bank.debit(new DebitRequest(txn, ravi, 20_000));

        BankResponse first = bank.reverse(new ReverseRequest(txn));
        BankResponse second = bank.reverse(new ReverseRequest(txn));
        BankResponse third = bank.reverse(new ReverseRequest(txn));

        assertEquals(BankResult.SUCCESS, first.result());
        assertEquals(first, second);
        assertEquals(first, third);
        assertEquals(500_000, balance(ravi));                  // not 520_000 or 540_000
        assertEquals(2, journalEntriesFor(txn));               // one debit, one reversal
    }

    @Test
    void reversingADeclinedDebitIsNotNoDebit() {
        // The debit row exists (declined), so this is not "no debit": it keeps its Phase 2 answer.
        String ravi = newCustomer(10_000);
        String txn = newTxnId();
        bank.debit(new DebitRequest(txn, ravi, 20_000));

        BankResponse r = bank.reverse(new ReverseRequest(txn));

        assertEquals(BankResult.DECLINED, r.result());
        assertEquals(DeclineReason.ORIGINAL_NOT_SUCCESSFUL, r.reason());
        assertEquals(TxnStatus.FAILED, bank.status(txn, OpType.REVERSE).status());   // a decline shows as FAILED
    }

    @Test
    void noDebitReverseShowsAsSuccessInReadOnlyStatus() {
        String txn = newTxnId();
        bank.reverse(new ReverseRequest(txn));

        // The reverse request was fully handled: the money is where it should be.
        assertEquals(TxnStatus.SUCCESS, bank.status(txn, OpType.REVERSE).status());
        assertEquals(TxnStatus.FAILED, bank.status(txn, OpType.DEBIT).status());
    }

    // ---- races: the per-txnId lock -------------------------------------------------------

    /**
     * A late debit and a fencing status-check arrive at the same instant, 30 times over. Exactly
     * one of two stories may happen, never a mix:
     * <ul>
     *   <li>the debit ran first: it succeeded, and the check FOUND it; or</li>
     *   <li>the check ran first: it FENCED, and the debit was declined.</li>
     * </ul>
     * The dangerous mix would be "check says FENCED, but the debit succeeded": the switch would
     * mark the payment FAILED while Ravi's money is gone.
     */
    @Test
    void lateDebitRacingAFenceEndsInOneConsistentStory() throws Exception {
        for (int i = 0; i < 30; i++) {
            String ravi = newCustomer(500_000);
            String txn = newTxnId();

            Object[] answers = together(
                    () -> bank.debit(new DebitRequest(txn, ravi, 20_000)),
                    () -> bank.statusCheck(new StatusCheckRequest(txn, OpType.DEBIT, true)));
            BankResponse debit = (BankResponse) answers[0];
            StatusCheckResponse check = (StatusCheckResponse) answers[1];

            if (debit.result() == BankResult.SUCCESS) {
                assertEquals(CheckResult.FOUND, check.result(), "round " + i);
                assertEquals(BankResult.SUCCESS, check.saved().result(), "round " + i);
                assertEquals(480_000, balance(ravi), "round " + i);
            } else {
                assertEquals(DeclineReason.FENCED, debit.reason(), "round " + i);
                assertEquals(CheckResult.FENCED, check.result(), "round " + i);
                assertEquals(500_000, balance(ravi), "round " + i);
            }
        }
    }

    /** A late debit and its reverse at the same instant: Ravi always ends with all his money. */
    @Test
    void lateDebitRacingItsReverseAlwaysLeavesRaviWhole() throws Exception {
        for (int i = 0; i < 30; i++) {
            String ravi = newCustomer(500_000);
            String txn = newTxnId();

            Object[] answers = together(
                    () -> bank.debit(new DebitRequest(txn, ravi, 20_000)),
                    () -> bank.reverse(new ReverseRequest(txn)));
            BankResponse debit = (BankResponse) answers[0];
            BankResponse reverse = (BankResponse) answers[1];

            if (debit.result() == BankResult.SUCCESS) {
                assertEquals(BankResult.SUCCESS, reverse.result(), "round " + i);   // undone
            } else {
                assertEquals(DeclineReason.ALREADY_REVERSED, debit.reason(), "round " + i);
                assertEquals(BankResult.NO_DEBIT, reverse.result(), "round " + i);
            }
            assertEquals(500_000, balance(ravi), "round " + i);
        }
    }

    /** Runs both calls on two threads released at the same moment; returns their answers in order. */
    private static Object[] together(Callable<?> a, Callable<?> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            Future<?> fa = pool.submit(() -> {
                go.await();
                return a.call();
            });
            Future<?> fb = pool.submit(() -> {
                go.await();
                return b.call();
            });
            go.countDown();
            return new Object[] {fa.get(), fb.get()};
        } finally {
            pool.shutdownNow();
        }
    }
}

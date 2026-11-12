package com.payswitch.bank;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.payswitch.common.bank.BankResponse;
import com.payswitch.common.bank.BankResult;
import com.payswitch.common.bank.CreditRequest;
import com.payswitch.common.bank.DebitRequest;
import com.payswitch.common.bank.DeclineReason;
import com.payswitch.common.bank.OpType;
import com.payswitch.common.bank.ReverseRequest;
import com.payswitch.common.bank.TxnStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class IdempotencyTest extends BankIntegrationTest {

    @Test
    void sameDebitSentFiveTimesMovesMoneyOnce() {
        String ravi = newCustomer(500_000);
        DebitRequest debit = new DebitRequest(newTxnId(), ravi, 20_000);

        BankResponse first = bank.debit(debit);
        for (int i = 0; i < 4; i++) {
            assertEquals(first, bank.debit(debit)); // the saved answer, word for word
        }

        assertEquals(480_000, balance(ravi));
        assertEquals(1, journalEntriesFor(debit.txnId()));
    }

    @Test
    void sameDebitSentFiveTimesAtTheSameMomentMovesMoneyOnce() throws Exception {
        String ravi = newCustomer(500_000);
        DebitRequest debit = new DebitRequest(newTxnId(), ravi, 20_000);

        List<BankResponse> answers = runConcurrently(5, i -> bank.debit(debit));

        answers.forEach(a -> assertEquals(BankResult.SUCCESS, a.result()));
        assertEquals(480_000, balance(ravi));
        assertEquals(1, journalEntriesFor(debit.txnId()));
    }

    @Test
    void declinedDebitStaysDeclinedOnRetryEvenAfterMoneyArrives() {
        String ravi = newCustomer(10_000);
        DebitRequest debit = new DebitRequest(newTxnId(), ravi, 20_000);
        assertEquals(BankResult.DECLINED, bank.debit(debit).result());

        bank.credit(new CreditRequest(newTxnId(), ravi, 50_000)); // Ravi now has enough

        // Same txnId = same question = same answer. A new attempt must use a new txnId.
        assertEquals(DeclineReason.INSUFFICIENT_FUNDS, bank.debit(debit).reason());
        assertEquals(60_000, balance(ravi));
    }

    @Test
    void reversingTwiceUndoesOnce() {
        String ravi = newCustomer(500_000);
        String txn = newTxnId();
        bank.debit(new DebitRequest(txn, ravi, 20_000));

        BankResponse first = bank.reverse(new ReverseRequest(txn));
        BankResponse second = bank.reverse(new ReverseRequest(txn));

        assertEquals(BankResult.SUCCESS, first.result());
        assertEquals(first, second);
        assertEquals(500_000, balance(ravi)); // not 520_000
    }

    @Test
    void reversingADebitThatNeverHappenedIsRejected() {
        String txn = newTxnId();

        BankResponse r = bank.reverse(new ReverseRequest(txn));

        assertEquals(BankResult.DECLINED, r.result());
        assertEquals(DeclineReason.ORIGINAL_NOT_FOUND, r.reason());
        assertEquals(0, journalEntriesFor(txn));
    }

    @Test
    void debitArrivingAfterItsReversalIsBlocked() {
        String ravi = newCustomer(500_000);
        String txn = newTxnId();
        bank.reverse(new ReverseRequest(txn)); // the reversal overtook the debit on the network

        BankResponse lateDebit = bank.debit(new DebitRequest(txn, ravi, 20_000));

        assertEquals(DeclineReason.ALREADY_REVERSED, lateDebit.reason());
        assertEquals(500_000, balance(ravi)); // no money stuck in settlement
        assertEquals(TxnStatus.FAILED, bank.status(txn, OpType.DEBIT).status());
    }

    @Test
    void statusReportsSuccessFailedAndNotFound() {
        String ravi = newCustomer(10_000);
        String ok = newTxnId();
        String bad = newTxnId();
        bank.debit(new DebitRequest(ok, ravi, 5_000));
        bank.debit(new DebitRequest(bad, ravi, 50_000));

        assertEquals(TxnStatus.SUCCESS, bank.status(ok, OpType.DEBIT).status());
        assertEquals(TxnStatus.FAILED, bank.status(bad, OpType.DEBIT).status());
        assertEquals(TxnStatus.NOT_FOUND, bank.status(ok, OpType.CREDIT).status());
        assertEquals(TxnStatus.NOT_FOUND, bank.status(newTxnId(), OpType.DEBIT).status());
    }

    interface Call {
        BankResponse run(int i) throws Exception;
    }

    /** Runs n calls on n threads, all released at the same instant by a latch. */
    static List<BankResponse> runConcurrently(int n, Call call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch startGun = new CountDownLatch(1);
            List<Future<BankResponse>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    startGun.await();
                    return call.run(index);
                }));
            }
            startGun.countDown();
            List<BankResponse> results = new ArrayList<>();
            for (Future<BankResponse> f : futures) {
                results.add(f.get()); // rethrows if any thread failed
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}

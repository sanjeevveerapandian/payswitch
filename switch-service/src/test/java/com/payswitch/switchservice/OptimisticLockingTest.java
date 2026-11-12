package com.payswitch.switchservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.payswitch.switchservice.domain.Txn;
import com.payswitch.switchservice.domain.TxnEvent;
import com.payswitch.switchservice.domain.TxnState;
import com.payswitch.switchservice.service.ConcurrentTransitionException;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class OptimisticLockingTest extends SwitchIntegrationTest {

    /**
     * The debit to Bank A timed out. Two workers both read the payment at version 1, then act at
     * the same moment: one got Bank A's late "debited" reply, the other gave up and wants to
     * reverse. Exactly one may win. Repeated, because thread timing differs on every run.
     */
    @RepeatedTest(20)
    void twoThreadsMovingTheSameTxnExactlyOneWins() throws Exception {
        Txn read = paymentIn(TxnState.DEBIT_SENT);          // both workers saw this snapshot
        CyclicBarrier bothReady = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Txn> lateReply = pool.submit(() -> {
                bothReady.await();
                return service.transition(read.txnId(), read.version(), TxnState.DEBITED, "late reply: debited");
            });
            Future<Txn> gaveUp = pool.submit(() -> {
                bothReady.await();
                return service.transition(read.txnId(), read.version(), TxnState.REVERSAL_SENT, "timeout: reversing");
            });

            Throwable failureA = failureOf(lateReply);
            Throwable failureB = failureOf(gaveUp);

            // Exactly one succeeded, and the loser failed with the optimistic-lock exception.
            assertTrue(failureA == null ^ failureB == null, "expected exactly one winner");
            assertInstanceOf(ConcurrentTransitionException.class, failureA != null ? failureA : failureB);

            Txn after = service.get(read.txnId());
            TxnState winner = failureA == null ? TxnState.DEBITED : TxnState.REVERSAL_SENT;
            assertEquals(winner, after.state());
            assertEquals(read.version() + 1, after.version());   // bumped once, not twice

            // The loser left no trace: one event for the winning move only.
            List<TxnEvent> events = service.history(read.txnId());
            assertEquals(3, events.size());                      // created, DEBIT_SENT, winner
            assertEquals(winner, events.get(2).toState());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void staleVersionIsRefusedEvenWithoutARace() {
        Txn read = paymentIn(TxnState.DEBIT_SENT);
        service.transition(read.txnId(), TxnState.DEBITED, "someone else moved it");

        // We still hold the old snapshot. DEBIT_SENT -> FAILED was legal when we read it.
        assertThrows(ConcurrentTransitionException.class,
                () -> service.transition(read.txnId(), read.version(), TxnState.FAILED, "stale decision"));

        assertEquals(TxnState.DEBITED, service.get(read.txnId()).state());
    }

    private static Throwable failureOf(Future<Txn> future) throws InterruptedException {
        try {
            future.get();
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }
}

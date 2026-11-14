package com.payswitch.switchservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.payswitch.common.bank.DeclineReason;
import com.payswitch.common.bank.OpType;
import com.payswitch.switchservice.bank.BankCallResult;
import com.payswitch.switchservice.domain.Txn;
import com.payswitch.switchservice.domain.TxnState;
import com.payswitch.switchservice.service.ResolverJob;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The background resolver: what it picks up, and that two of them never share a payment. */
class ResolverJobTest extends ResolutionIntegrationTest {

    /** A payment the switch saved as DEBIT_SENT, then "crashed" before hearing back. */
    private String stuckInDebitSent() {
        String id = "TXN-" + UUID.randomUUID();
        Txn txn = service.create(id, "ravi@banka", "anu@bankb", 20_000);
        service.transition(id, txn.version(), TxnState.DEBIT_SENT, "DEBIT_REQUESTED:BANKA");
        return id;
    }

    /**
     * 20 stuck payments, two resolvers started at the same instant (as if two copies of the switch
     * were running). Every payment must be claimed by exactly one of them, and its bank asked
     * exactly once. SKIP LOCKED is what makes them take different rows instead of the same ones.
     */
    @Test
    void twoResolversAtOnceHandleEachStuckPaymentExactlyOnce() throws Exception {
        List<String> ours = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ours.add(stuckInDebitSent());
        }
        Map<String, AtomicInteger> checksPerTxn = new ConcurrentHashMap<>();
        when(banks.statusCheckAndFence(any(), anyString(), eq(OpType.DEBIT))).thenAnswer(call -> {
            checksPerTxn.computeIfAbsent(call.getArgument(1), k -> new AtomicInteger()).incrementAndGet();
            Thread.sleep(20);                       // slow enough for the two resolvers to overlap
            return BankCallResult.declined(DeclineReason.FENCED);
        });
        ResolverJob first = new ResolverJob(service, flow, props);
        ResolverJob second = new ResolverJob(service, flow, props);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<String> byFirst;
        List<String> bySecond;
        try {
            CountDownLatch go = new CountDownLatch(1);
            Future<List<String>> a = pool.submit(() -> {
                go.await();
                return first.runOnce();
            });
            Future<List<String>> b = pool.submit(() -> {
                go.await();
                return second.runOnce();
            });
            go.countDown();
            byFirst = a.get();
            bySecond = b.get();
        } finally {
            pool.shutdownNow();
        }

        Set<String> oursByFirst = new HashSet<>(byFirst);
        oursByFirst.retainAll(ours);
        Set<String> oursBySecond = new HashSet<>(bySecond);
        oursBySecond.retainAll(ours);

        Set<String> both = new HashSet<>(oursByFirst);
        both.retainAll(oursBySecond);
        assertTrue(both.isEmpty(), "claimed by both: " + both);
        assertEquals(20, oursByFirst.size() + oursBySecond.size());
        assertFalse(oursByFirst.isEmpty(), "the first resolver did no work, so this proved nothing");
        assertFalse(oursBySecond.isEmpty(), "the second resolver did no work, so this proved nothing");
        for (String id : ours) {
            assertEquals(1, checksPerTxn.get(id).get(), "bank asked about " + id);
            assertEquals(TxnState.FAILED, service.get(id).state());
        }
    }

    @Test
    void aLeasedPaymentIsLeftAlone() {
        String id = stuckInDebitSent();
        service.lease(id, Duration.ofHours(1));     // someone else is working on it

        List<String> handled = job.runOnce();

        assertFalse(handled.contains(id));
        assertEquals(TxnState.DEBIT_SENT, service.get(id).state());
    }

    @Test
    void finishedAndParkedPaymentsAreNeverPickedUp() {
        when(banks.statusCheckAndFence(any(), anyString(), any())).thenReturn(BankCallResult.declined(DeclineReason.FENCED));
        String failed = stuckInDebitSent();
        Txn t = service.get(failed);
        service.transition(failed, t.version(), TxnState.FAILED, "test");

        List<String> handled = job.runOnce();

        assertFalse(handled.contains(failed));
    }
}

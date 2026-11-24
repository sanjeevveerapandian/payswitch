package com.payswitch.switchservice.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payswitch.common.pay.PayState;
import com.payswitch.common.pay.ReqPay;
import com.payswitch.common.pay.RespPay;
import com.payswitch.switchservice.domain.Txn;
import com.payswitch.switchservice.domain.TxnState;
import com.payswitch.switchservice.service.PaymentOrchestrator;
import com.payswitch.switchservice.service.TransactionService;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Phase 13: the scenarios are safe for a public demo. One at a time; the rest are told "busy";
 * the lock and the chaos a scenario set up are given back however the scenario ends.
 * Banks, payments and the crash are all fakes here: this is only about the rules around them.
 */
class ScenarioSafetyTest {

    private PaymentOrchestrator orchestrator;
    private TransactionService txns;
    private DemoCrash crash;
    private DemoBankAdmin banks;
    private DemoLock lock;
    private ScenarioService scenarios;

    @BeforeEach
    void setUp() {
        orchestrator = mock(PaymentOrchestrator.class);
        txns = mock(TransactionService.class);
        crash = mock(DemoCrash.class);
        banks = mock(DemoBankAdmin.class);
        lock = new DemoLock();
        scenarios = new ScenarioService(orchestrator, txns, crash, banks, lock);
        when(txns.get(anyString())).thenAnswer(call -> new Txn(call.getArgument(0), "ravi@banka", "anu@bankb",
                "BANKA", "BANKB", 1, TxnState.SUCCESS, 4, Instant.now(), Instant.now()));
    }

    private static RespPay success(ReqPay req) {
        return new RespPay(req.txnId(), PayState.SUCCESS, "CREDIT_OK");
    }

    @Test
    void whileOneScenarioRunsAnotherIsToldBusy() throws Exception {
        CountDownLatch paying = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(orchestrator.pay(any())).thenAnswer(call -> {
            paying.countDown();
            finish.await(10, TimeUnit.SECONDS);
            return success(call.getArgument(0));
        });

        CompletableFuture<ScenarioService.Result> first = CompletableFuture.supplyAsync(() -> scenarios.run("happy"));
        assertTrue(paying.await(10, TimeUnit.SECONDS), "the first scenario is paying");

        DemoLock.DemoBusyException busy = assertThrows(DemoLock.DemoBusyException.class,
                () -> scenarios.run("insufficient-funds"));
        assertTrue(busy.getMessage().contains("\"happy\""), busy.getMessage());
        assertTrue(busy.getMessage().contains("Try again in a few seconds"), busy.getMessage());

        finish.countDown();
        assertEquals("SUCCESS", first.get(10, TimeUnit.SECONDS).state());
        assertEquals("SUCCESS", scenarios.run("happy").state(), "free again once the first one finished");
    }

    @Test
    void aScenarioThatBlowsUpStillFreesTheLock() {
        when(orchestrator.pay(any())).thenThrow(new IllegalStateException("database down"));
        assertThrows(IllegalStateException.class, () -> scenarios.run("happy"));

        doAnswer(call -> success(call.getArgument(0))).when(orchestrator).pay(any());
        assertEquals("SUCCESS", scenarios.run("happy").state());
    }

    @Test
    void anUnknownNameNeverTakesTheLock() {
        assertThrows(IllegalArgumentException.class, () -> scenarios.run("nope"));
        when(orchestrator.pay(any())).thenAnswer(call -> success(call.getArgument(0)));
        assertEquals("SUCCESS", scenarios.run("happy").state());
    }

    @Test
    void debitReplyLostTakesBackItsOneShotRuleEvenIfThePaymentFails() {
        when(orchestrator.pay(any())).thenThrow(new IllegalStateException("never reached the bank"));

        assertThrows(IllegalStateException.class, () -> scenarios.run("debit-reply-lost"));

        InOrder order = inOrder(banks);
        order.verify(banks).armOnce("BANKA", "DEBIT", "drop_response");
        order.verify(banks).disarmOnce("BANKA", "DEBIT");
    }

    @Test
    void switchCrashFreesTheLockAndStandsDownWhenItsPaymentNeverGetsADebit() throws Exception {
        CountDownLatch paid = new CountDownLatch(1);
        when(orchestrator.pay(any())).thenAnswer(call -> {
            paid.countDown();
            ReqPay req = call.getArgument(0);
            return new RespPay(req.txnId(), PayState.FAILED, "DEBIT_DECLINED:INSUFFICIENT_FUNDS");
        });

        ScenarioService.Result r = scenarios.run("switch-crash");
        verify(crash).arm(r.txnId());                      // armed for its own payment only
        assertTrue(paid.await(10, TimeUnit.SECONDS));

        // The background payment ends: the crash is disarmed and the lock is free again.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            try {
                lock.acquire("test");
                lock.release();
                verify(crash).disarm(r.txnId());
                return;
            } catch (DemoLock.DemoBusyException stillRunning) {
                Thread.sleep(20);
            }
        }
        throw new AssertionError("the switch-crash scenario never gave the lock back");
    }

    @Test
    void scenariosAndTheReseedShareOneLock() {
        DemoReseedService reseed = new DemoReseedService(orchestrator, banks, lock);
        lock.acquire("credit-unknown-deemed");
        assertThrows(DemoLock.DemoBusyException.class, reseed::reseed);
        verify(banks, never()).balance(anyString());
        lock.release();
    }
}

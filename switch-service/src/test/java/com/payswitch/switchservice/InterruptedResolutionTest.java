package com.payswitch.switchservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payswitch.common.bank.OpType;
import com.payswitch.switchservice.bank.BankCallResult;
import com.payswitch.switchservice.domain.Txn;
import com.payswitch.switchservice.domain.TxnState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Review finding #8. When the switch shuts down, it interrupts its resolver threads. An interrupt
 * says "stop", not "the bank never answered". The resolver must stop and leave the payment exactly
 * where it was, so that after the restart it is resolved on real evidence.
 *
 * <p>The mocked bank interrupts the calling thread itself, the same thing a shutdown does to a
 * thread in the middle of its retries.
 */
class InterruptedResolutionTest extends ResolutionIntegrationTest {

    private final List<String> leftUnfinished = new ArrayList<>();

    /**
     * These tests leave payments unfinished ON PURPOSE. Lease them far into the future, so the
     * background job run by other test classes (same database) never picks them up.
     */
    @AfterEach
    void clearInterruptFlagAndParkOurPayments() {
        Thread.interrupted();
        leftUnfinished.forEach(id -> service.lease(id, Duration.ofDays(1)));
    }

    private String paymentIn(List<TxnState> steps) {
        String id = "TXN-" + UUID.randomUUID();
        leftUnfinished.add(id);
        Txn txn = service.create(id, "ravi@banka", "anu@bankb", 20_000);
        for (TxnState step : steps) {
            txn = service.transition(id, txn.version(), step, "test setup");
        }
        return id;
    }

    private static BankCallResult interruptMeThenAnswerUnknown() {
        Thread.currentThread().interrupt();                 // "the switch is shutting down"
        return BankCallResult.unknown("timeout");
    }

    @Test
    void interruptedWhileCheckingACreditDoesNotDeemItApproved() {
        String id = paymentIn(List.of(TxnState.DEBIT_SENT, TxnState.DEBITED, TxnState.CREDIT_SENT));
        when(banks.statusCheckAndFence(any(), eq(id), eq(OpType.CREDIT)))
                .thenAnswer(call -> interruptMeThenAnswerUnknown());

        flow.resolve(id);

        assertEquals(TxnState.CREDIT_SENT, service.get(id).state());   // not DEEMED_APPROVED
        assertTrue(Thread.currentThread().isInterrupted(), "the interrupt must not be swallowed");
    }

    @Test
    void interruptedWhileCheckingADebitDoesNotStartAReversal() {
        String id = paymentIn(List.of(TxnState.DEBIT_SENT));
        when(banks.statusCheckAndFence(any(), eq(id), eq(OpType.DEBIT)))
                .thenAnswer(call -> interruptMeThenAnswerUnknown());

        flow.resolve(id);

        assertEquals(TxnState.DEBIT_SENT, service.get(id).state());    // not REVERSAL_SENT
        verify(banks, never()).reverse(any(), any());
    }

    @Test
    void interruptedWhileRetryingAReversalLeavesItForLater() {
        String id = paymentIn(List.of(TxnState.DEBIT_SENT, TxnState.DEBITED, TxnState.CREDIT_SENT,
                TxnState.REVERSAL_SENT));
        when(banks.reverse(any(), any())).thenAnswer(call -> interruptMeThenAnswerUnknown());
        int eventsBefore = service.history(id).size();

        flow.resolve(id);

        assertEquals(TxnState.REVERSAL_SENT, service.get(id).state());
        // No "REVERSAL_UNCONFIRMED after N attempts" note: it did not really try N times.
        assertEquals(eventsBefore, service.history(id).size());
    }
}

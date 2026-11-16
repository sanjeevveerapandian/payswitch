package com.payswitch.switchservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payswitch.common.bank.OpType;
import com.payswitch.common.pay.PayState;
import com.payswitch.common.pay.ReqPay;
import com.payswitch.common.pay.RespPay;
import com.payswitch.switchservice.bank.BankCallResult;
import com.payswitch.switchservice.domain.Txn;
import com.payswitch.switchservice.domain.TxnState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Resolver paths JaCoCo showed were never run by any test. */
class ResolverEdgeCasesTest extends ResolutionIntegrationTest {

    private final List<String> ours = new ArrayList<>();

    /** Park whatever we leave unfinished, so other test classes' job runs never touch it. */
    @AfterEach
    void parkOurPayments() {
        Thread.interrupted();
        ours.forEach(id -> service.lease(id, Duration.ofDays(1)));
    }

    private String stuckInDebitSent() {
        String id = "TXN-" + UUID.randomUUID();
        ours.add(id);
        Txn txn = service.create(id, "ravi@banka", "anu@bankb", 20_000);
        service.transition(id, txn.version(), TxnState.DEBIT_SENT, "DEBIT_REQUESTED:BANKA");
        return id;
    }

    /**
     * While this resolver is waiting for Bank A, another resolver decides first (here: FAILED).
     * This one must notice (version check), stop, and not overwrite that decision.
     */
    @Test
    void whenAnotherResolverDecidesFirstThisOneStepsBack() {
        String id = stuckInDebitSent();
        when(banks.statusCheckAndFence(any(), eq(id), eq(OpType.DEBIT))).thenAnswer(call -> {
            Txn now = service.get(id);
            service.transition(id, now.version(), TxnState.FAILED, "decided by the other resolver");
            return BankCallResult.success();                    // our (now outdated) answer
        });

        flow.resolve(id);

        assertEquals(TxnState.FAILED, service.get(id).state());
        verify(banks, never()).credit(any(), any());
    }

    /** A bug inside resolution must not turn into a 500, or into a decision: just PENDING. */
    @Test
    void aCrashDuringInlineResolutionStillAnswersPending() {
        ReqPay req = raviPaysAnu200();
        ours.add(req.txnId());
        when(banks.debit(any(), any())).thenReturn(BankCallResult.unknown("timeout"));
        when(banks.statusCheckAndFence(any(), eq(req.txnId()), any())).thenThrow(new IllegalStateException("bug"));

        RespPay resp = orchestrator.pay(req);

        assertEquals(PayState.PENDING, resp.state());
        assertEquals(TxnState.DEBIT_SENT, service.get(req.txnId()).state());
    }

    /**
     * Shutdown in the middle of a batch: the payment being worked on is left as it was (finding #8),
     * and the rest of the batch is not touched at all.
     */
    @Test
    void aJobInterruptedMidBatchLeavesTheRestOfTheBatchAlone() {
        String first = stuckInDebitSent();
        String second = stuckInDebitSent();
        when(banks.statusCheckAndFence(any(), anyString(), eq(OpType.DEBIT))).thenAnswer(call -> {
            Thread.currentThread().interrupt();                 // "the switch is shutting down"
            return BankCallResult.unknown("timeout");
        });

        job.runOnce();

        // Exactly one payment was asked about; nothing was decided for either.
        verify(banks, times(1)).statusCheckAndFence(any(), anyString(), any());
        assertEquals(TxnState.DEBIT_SENT, service.get(first).state());
        assertEquals(TxnState.DEBIT_SENT, service.get(second).state());
    }
}

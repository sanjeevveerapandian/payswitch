package com.payswitch.switchservice;

import static com.payswitch.switchservice.domain.TxnState.CREDIT_SENT;
import static com.payswitch.switchservice.domain.TxnState.DEBITED;
import static com.payswitch.switchservice.domain.TxnState.DEBIT_SENT;
import static com.payswitch.switchservice.domain.TxnState.DEEMED_APPROVED;
import static com.payswitch.switchservice.domain.TxnState.FAILED;
import static com.payswitch.switchservice.domain.TxnState.INITIATED;
import static com.payswitch.switchservice.domain.TxnState.REVERSAL_SENT;
import static com.payswitch.switchservice.domain.TxnState.REVERSED;
import static com.payswitch.switchservice.domain.TxnState.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payswitch.common.bank.DeclineReason;
import com.payswitch.common.bank.OpType;
import com.payswitch.common.pay.PayState;
import com.payswitch.common.pay.ReqPay;
import com.payswitch.common.pay.RespPay;
import com.payswitch.switchservice.bank.BankCallResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the switch decides once a bank's answer was UNKNOWN. Banks are scripted per test. */
class ResolutionTest extends ResolutionIntegrationTest {

    private static final BankCallResult OK = BankCallResult.success();
    private static final BankCallResult LOST = BankCallResult.unknown("timeout");
    private static final BankCallResult FENCED = BankCallResult.declined(DeclineReason.FENCED);

    // ---- debit UNKNOWN ------------------------------------------------------------------

    @Test
    void debitUnknownButStatusSaysItHappenedSoThePaymentCarriesOn() {
        ReqPay req = raviPaysAnu200();
        when(banks.debit(any(), any())).thenReturn(LOST);
        when(banks.statusCheckAndFence(any(), any(), eq(OpType.DEBIT))).thenReturn(OK);
        when(banks.credit(any(), any())).thenReturn(OK);

        RespPay resp = orchestrator.pay(req);

        assertEquals(PayState.SUCCESS, resp.state());
        assertEquals(List.of(INITIATED, DEBIT_SENT, DEBIT_SENT, DEBITED, CREDIT_SENT, SUCCESS), path(req.txnId()));
        assertEquals("DEBIT_UNKNOWN: timeout", reasons(req.txnId()).get(2));
        assertEquals("DEBIT_CONFIRMED_BY_STATUS", reasons(req.txnId()).get(3));
        verify(banks, never()).reverse(any(), any());
    }

    @Test
    void debitUnknownAndTheBankFencesItSoFailed() {
        ReqPay req = raviPaysAnu200();
        when(banks.debit(any(), any())).thenReturn(LOST);
        when(banks.statusCheckAndFence(any(), any(), eq(OpType.DEBIT))).thenReturn(FENCED);

        RespPay resp = orchestrator.pay(req);

        assertEquals(new RespPay(req.txnId(), PayState.FAILED, "DEBIT_DECLINED:FENCED"), resp);
        verify(banks, never()).credit(any(), any());
        verify(banks, never()).reverse(any(), any());
    }

    @Test
    void debitStillUnknownAfterEveryCheckSoReverseToBeSafe() {
        ReqPay req = raviPaysAnu200();
        when(banks.debit(any(), any())).thenReturn(LOST);
        when(banks.statusCheckAndFence(any(), any(), eq(OpType.DEBIT))).thenReturn(BankCallResult.unknown("HTTP 500"));
        when(banks.reverse(any(), any())).thenReturn(OK);

        RespPay resp = orchestrator.pay(req);

        assertEquals(PayState.REVERSED, resp.state());
        assertEquals(List.of(INITIATED, DEBIT_SENT, DEBIT_SENT, REVERSAL_SENT, REVERSED), path(req.txnId()));
        assertEquals("DEBIT_STATUS_UNKNOWN: HTTP 500", reasons(req.txnId()).get(3));
        verify(banks, times(3)).statusCheckAndFence(any(), eq(req.txnId()), eq(OpType.DEBIT));   // max-attempts
        verify(banks, never()).credit(any(), any());
    }

    @Test
    void reversalThatFindsNoDebitIsReversedToo() {
        ReqPay req = raviPaysAnu200();
        when(banks.debit(any(), any())).thenReturn(LOST);
        when(banks.statusCheckAndFence(any(), any(), eq(OpType.DEBIT))).thenReturn(BankCallResult.unknown("HTTP 500"));
        when(banks.reverse(any(), any())).thenReturn(BankCallResult.successNoDebit());

        RespPay resp = orchestrator.pay(req);

        assertEquals(new RespPay(req.txnId(), PayState.REVERSED, "REVERSAL_OK:NO_DEBIT"), resp);
    }

    // ---- credit UNKNOWN -----------------------------------------------------------------

    @Test
    void creditUnknownButStatusSaysItHappenedSoSuccess() {
        ReqPay req = raviPaysAnu200();
        when(banks.debit(any(), any())).thenReturn(OK);
        when(banks.credit(any(), any())).thenReturn(LOST);
        when(banks.statusCheckAndFence(any(), any(), eq(OpType.CREDIT))).thenReturn(OK);

        RespPay resp = orchestrator.pay(req);

        assertEquals(new RespPay(req.txnId(), PayState.SUCCESS, "CREDIT_CONFIRMED_BY_STATUS"), resp);
        verify(banks, never()).reverse(any(), any());
    }

    @Test
    void creditUnknownAndFencedSoThePayerIsRefunded() {
        ReqPay req = raviPaysAnu200();
        when(banks.debit(any(), any())).thenReturn(OK);
        when(banks.credit(any(), any())).thenReturn(LOST);
        when(banks.statusCheckAndFence(any(), any(), eq(OpType.CREDIT))).thenReturn(FENCED);
        when(banks.reverse(any(), any())).thenReturn(OK);

        RespPay resp = orchestrator.pay(req);

        assertEquals(PayState.REVERSED, resp.state());
        assertEquals(List.of(INITIATED, DEBIT_SENT, DEBITED, CREDIT_SENT, CREDIT_SENT, REVERSAL_SENT, REVERSED),
                path(req.txnId()));
        assertEquals("CREDIT_DECLINED:FENCED", reasons(req.txnId()).get(5));
    }

    /** The one place where the switch must NOT fall back to a reversal. */
    @Test
    void creditStillUnknownIsDeemedApprovedAndTheDebitIsNeverReversed() {
        ReqPay req = raviPaysAnu200();
        when(banks.debit(any(), any())).thenReturn(OK);
        when(banks.credit(any(), any())).thenReturn(LOST);
        when(banks.statusCheckAndFence(any(), any(), eq(OpType.CREDIT))).thenReturn(BankCallResult.unknown("HTTP 500"));

        RespPay resp = orchestrator.pay(req);

        assertEquals(PayState.PENDING, resp.state());
        assertEquals(DEEMED_APPROVED, service.get(req.txnId()).state());
        assertEquals("CREDIT_STATUS_UNKNOWN: HTTP 500", resp.reason());
        verify(banks, never()).reverse(any(), any());
        // The background job leaves it alone too: DEEMED_APPROVED is not "stuck", it is parked.
        job.runOnce();
        verify(banks, never()).reverse(any(), any());
    }

    // ---- reversal -----------------------------------------------------------------------

    @Test
    void reversalIsRetriedUntilTheBankConfirms() {
        ReqPay req = raviPaysAnu200();
        when(banks.debit(any(), any())).thenReturn(OK);
        when(banks.credit(any(), any())).thenReturn(BankCallResult.declined(DeclineReason.UNKNOWN_VPA));
        // First attempt: lost. Resolver: lost again, then confirmed.
        when(banks.reverse(any(), any())).thenReturn(LOST, LOST, OK);

        RespPay resp = orchestrator.pay(req);

        assertEquals(new RespPay(req.txnId(), PayState.REVERSED, "REVERSAL_OK"), resp);
        verify(banks, times(3)).reverse(any(), any());
    }

    @Test
    void reversalNotConfirmedYetIsPickedUpLaterByTheJob() throws Exception {
        ReqPay req = raviPaysAnu200();
        when(banks.debit(any(), any())).thenReturn(OK);
        when(banks.credit(any(), any())).thenReturn(BankCallResult.declined(DeclineReason.UNKNOWN_VPA));
        when(banks.reverse(any(), any())).thenReturn(LOST);       // the bank is down for now

        RespPay first = orchestrator.pay(req);

        assertEquals(PayState.PENDING, first.state());
        assertEquals(REVERSAL_SENT, service.get(req.txnId()).state());
        assertTrue(reasons(req.txnId()).contains("REVERSAL_UNCONFIRMED: timeout"));

        when(banks.reverse(any(), any())).thenReturn(OK);         // the bank is back
        Thread.sleep(props.lease().toMillis() + 100);             // let the inline lease run out
        List<String> handled = job.runOnce();

        assertTrue(handled.contains(req.txnId()));
        assertEquals(REVERSED, service.get(req.txnId()).state());
    }

    // ---- inline wait --------------------------------------------------------------------

    @Test
    void slowResolutionAnswersPendingFirstAndFinishesInTheBackground() throws Exception {
        ReqPay req = raviPaysAnu200();
        when(banks.debit(any(), any())).thenReturn(LOST);
        when(banks.statusCheckAndFence(any(), any(), eq(OpType.DEBIT))).thenAnswer(call -> {
            Thread.sleep(2_500);                                   // longer than inline-wait (2s)
            return OK;
        });
        when(banks.credit(any(), any())).thenReturn(OK);

        long start = System.nanoTime();
        RespPay resp = orchestrator.pay(req);
        long tookMs = (System.nanoTime() - start) / 1_000_000;

        // PENDING proves it did not wait for the 2.5 s status check; >= 2 s proves it did wait inline-wait.
        assertEquals(PayState.PENDING, resp.state());
        assertTrue(tookMs >= 2_000, "answered after " + tookMs + " ms");
        // ...and GET /pay/{txnId} will show the final answer a moment later.
        long deadline = System.currentTimeMillis() + 5_000;
        while (service.get(req.txnId()).state() != SUCCESS && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(SUCCESS, service.get(req.txnId()).state());
    }

    @Test
    void aPaymentThatFailedForGoodIsNeverTouchedAgain() {
        ReqPay req = raviPaysAnu200();
        when(banks.debit(any(), any())).thenReturn(BankCallResult.declined(DeclineReason.INSUFFICIENT_FUNDS));

        assertEquals(PayState.FAILED, orchestrator.pay(req).state());
        verify(banks, never()).statusCheckAndFence(any(), any(), any());
        assertEquals(FAILED, service.get(req.txnId()).state());
    }
}

package com.payswitch.switchservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payswitch.common.bank.CreditRequest;
import com.payswitch.common.bank.DebitRequest;
import com.payswitch.common.bank.DeclineReason;
import com.payswitch.common.bank.ReverseRequest;
import com.payswitch.common.pay.PayState;
import com.payswitch.common.pay.ReqPay;
import com.payswitch.common.pay.RespPay;
import com.payswitch.switchservice.bank.BankCallResult;
import com.payswitch.switchservice.bank.BankClient;
import com.payswitch.switchservice.domain.TxnEvent;
import com.payswitch.switchservice.domain.TxnState;
import com.payswitch.switchservice.domain.TxnStateMachine;
import com.payswitch.switchservice.routing.BankRoute;
import com.payswitch.switchservice.routing.SwitchProperties;
import com.payswitch.switchservice.service.PaymentOrchestrator;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The switch's decisions, with the banks replaced by a fake we script per test
 * ("Bank A says yes, Bank B times out"). Real Postgres, no real HTTP to banks.
 */
@AutoConfigureMockMvc
class PaymentOrchestrationTest extends SwitchIntegrationTest {

    private static final BankRoute BANK_A = new BankRoute("BANKA", URI.create("http://localhost:8081"));
    private static final BankRoute BANK_B = new BankRoute("BANKB", URI.create("http://localhost:8082"));

    private static final BankCallResult OK = BankCallResult.success();

    @MockitoBean
    private BankClient banks;

    @Autowired
    private PaymentOrchestrator orchestrator;

    @Autowired
    private SwitchProperties properties;

    @Autowired
    private MockMvc mvc;

    private ReqPay raviPaysAnu200() {
        return new ReqPay(newTxnId(), "ravi@banka", "anu@bankb", 20_000);
    }

    // ---- clear answers (unchanged behaviour from Phase 4) ------------------------------

    @Test
    void happyPathDebitsBankAThenCreditsBankB() {
        ReqPay req = raviPaysAnu200();
        when(banks.debit(any(), any())).thenReturn(OK);
        when(banks.credit(any(), any())).thenReturn(OK);

        RespPay resp = orchestrator.pay(req);

        assertEquals(new RespPay(req.txnId(), PayState.SUCCESS, "CREDIT_OK"), resp);
        assertEquals(List.of(TxnState.INITIATED, TxnState.DEBIT_SENT, TxnState.DEBITED,
                TxnState.CREDIT_SENT, TxnState.SUCCESS), path(req.txnId()));
        verify(banks).debit(BANK_A, new DebitRequest(req.txnId(), "ravi@banka", 20_000));
        verify(banks).credit(BANK_B, new CreditRequest(req.txnId(), "anu@bankb", 20_000));
        verify(banks, never()).reverse(any(), any());
    }

    @Test
    void stateIsSavedBeforeEachBankCall() {
        ReqPay req = raviPaysAnu200();
        List<TxnState> stateSeenByBank = new ArrayList<>();
        when(banks.debit(any(), any())).thenAnswer(call -> {
            stateSeenByBank.add(service.get(req.txnId()).state());   // what is in the DB right now?
            return OK;
        });
        when(banks.credit(any(), any())).thenAnswer(call -> {
            stateSeenByBank.add(service.get(req.txnId()).state());
            return BankCallResult.declined(DeclineReason.UNKNOWN_VPA);
        });
        when(banks.reverse(any(), any())).thenAnswer(call -> {
            stateSeenByBank.add(service.get(req.txnId()).state());
            return OK;
        });

        orchestrator.pay(req);

        assertEquals(List.of(TxnState.DEBIT_SENT, TxnState.CREDIT_SENT, TxnState.REVERSAL_SENT), stateSeenByBank);
    }

    @Test
    void debitDeclinedMeansFailedAndBankBIsNeverCalled() {
        ReqPay req = raviPaysAnu200();
        when(banks.debit(any(), any())).thenReturn(BankCallResult.declined(DeclineReason.INSUFFICIENT_FUNDS));

        RespPay resp = orchestrator.pay(req);

        assertEquals(new RespPay(req.txnId(), PayState.FAILED, "DEBIT_DECLINED:INSUFFICIENT_FUNDS"), resp);
        assertEquals(List.of(TxnState.INITIATED, TxnState.DEBIT_SENT, TxnState.FAILED), path(req.txnId()));
        verify(banks, never()).credit(any(), any());
        verify(banks, never()).reverse(any(), any());
    }

    @Test
    void creditDeclinedMeansThePayerIsRefunded() {
        ReqPay req = raviPaysAnu200();
        when(banks.debit(any(), any())).thenReturn(OK);
        when(banks.credit(any(), any())).thenReturn(BankCallResult.declined(DeclineReason.UNKNOWN_VPA));
        when(banks.reverse(any(), any())).thenReturn(OK);

        RespPay resp = orchestrator.pay(req);

        assertEquals(new RespPay(req.txnId(), PayState.REVERSED, "REVERSAL_OK"), resp);
        assertEquals(List.of(TxnState.INITIATED, TxnState.DEBIT_SENT, TxnState.DEBITED, TxnState.CREDIT_SENT,
                TxnState.REVERSAL_SENT, TxnState.REVERSED), path(req.txnId()));
        assertEquals("CREDIT_DECLINED:UNKNOWN_VPA", service.history(req.txnId()).get(4).reason());
        verify(banks).reverse(BANK_A, new ReverseRequest(req.txnId()));   // refund goes to the PAYER's bank
    }

    @Test
    void reversalDeclinedBecauseThereWasNoDebitMeansFailed() {
        ReqPay req = raviPaysAnu200();
        when(banks.debit(any(), any())).thenReturn(OK);
        when(banks.credit(any(), any())).thenReturn(BankCallResult.declined(DeclineReason.UNKNOWN_VPA));
        when(banks.reverse(any(), any())).thenReturn(BankCallResult.declined(DeclineReason.ORIGINAL_NOT_FOUND));

        RespPay resp = orchestrator.pay(req);

        assertEquals(new RespPay(req.txnId(), PayState.FAILED, "REVERSAL_DECLINED:ORIGINAL_NOT_FOUND"), resp);
    }

    @Test
    void unknownHandleFailsWithoutCallingAnyBank() {
        ReqPay req = new ReqPay(newTxnId(), "ravi@banka", "anu@nobank", 20_000);

        RespPay resp = orchestrator.pay(req);

        assertEquals(new RespPay(req.txnId(), PayState.FAILED, "UNKNOWN_HANDLE"), resp);
        verify(banks, never()).debit(any(), any());
    }

    @Test
    void sameReqPaySentTwiceRunsThePaymentOnce() {
        ReqPay req = raviPaysAnu200();
        when(banks.debit(any(), any())).thenReturn(OK);
        when(banks.credit(any(), any())).thenReturn(OK);

        RespPay first = orchestrator.pay(req);
        RespPay second = orchestrator.pay(req);

        assertEquals(first, second);
        verify(banks, times(1)).debit(any(), eq(new DebitRequest(req.txnId(), "ravi@banka", 20_000)));
        verify(banks, times(1)).credit(any(), eq(new CreditRequest(req.txnId(), "anu@bankb", 20_000)));
        assertEquals(5, service.history(req.txnId()).size());
    }

    // ---- UNKNOWN: no clear answer from a bank (new in Phase 5) -------------------------

    /**
     * Replaces Phase 4's "if the bank call throws, the payment stays in DEBIT_SENT". BankClient
     * no longer throws; it answers UNKNOWN, and the switch records it and says PENDING.
     */
    @Test
    void unknownDebitStaysInDebitSentAndThePayerIsToldPending() {
        ReqPay req = raviPaysAnu200();
        when(banks.debit(any(), any())).thenReturn(BankCallResult.unknown("timeout"));

        RespPay resp = orchestrator.pay(req);

        assertEquals(new RespPay(req.txnId(), PayState.PENDING, "DEBIT_UNKNOWN: timeout"), resp);
        assertEquals(TxnState.DEBIT_SENT, service.get(req.txnId()).state());
        TxnEvent note = last(req.txnId());
        assertEquals(TxnState.DEBIT_SENT, note.fromState());     // a note, not a move
        assertEquals(TxnState.DEBIT_SENT, note.toState());
        verify(banks, never()).credit(any(), any());              // never pay out on a maybe
        verify(banks, never()).reverse(any(), any());             // never refund on a maybe either
    }

    @Test
    void unknownCreditStaysInCreditSentWithoutReversing() {
        ReqPay req = raviPaysAnu200();
        when(banks.debit(any(), any())).thenReturn(OK);
        when(banks.credit(any(), any())).thenReturn(BankCallResult.unknown("HTTP 500"));

        RespPay resp = orchestrator.pay(req);

        assertEquals(new RespPay(req.txnId(), PayState.PENDING, "CREDIT_UNKNOWN: HTTP 500"), resp);
        assertEquals(TxnState.CREDIT_SENT, service.get(req.txnId()).state());
        // Anu MAY have the money. A reversal now could pay Rs 200 out twice.
        verify(banks, never()).reverse(any(), any());
    }

    @Test
    void unknownReversalStaysInReversalSent() {
        ReqPay req = raviPaysAnu200();
        when(banks.debit(any(), any())).thenReturn(OK);
        when(banks.credit(any(), any())).thenReturn(BankCallResult.declined(DeclineReason.UNKNOWN_VPA));
        when(banks.reverse(any(), any())).thenReturn(BankCallResult.unknown("connection refused"));

        RespPay resp = orchestrator.pay(req);

        assertEquals(new RespPay(req.txnId(), PayState.PENDING, "REVERSAL_UNKNOWN: connection refused"), resp);
        assertEquals(TxnState.REVERSAL_SENT, service.get(req.txnId()).state());
    }

    /** A "no" that does not say "there was no debit" does not prove Ravi has his money back. */
    @Test
    void reversalDeclinedForAnyOtherReasonStaysInReversalSent() {
        ReqPay req = raviPaysAnu200();
        when(banks.debit(any(), any())).thenReturn(OK);
        when(banks.credit(any(), any())).thenReturn(BankCallResult.declined(DeclineReason.UNKNOWN_VPA));
        when(banks.reverse(any(), any())).thenReturn(BankCallResult.declined(DeclineReason.SIMULATED_DECLINE));

        RespPay resp = orchestrator.pay(req);

        assertEquals(new RespPay(req.txnId(), PayState.PENDING, "REVERSAL_DECLINED: SIMULATED_DECLINE"), resp);
        assertEquals(TxnState.REVERSAL_SENT, service.get(req.txnId()).state());
    }

    @Test
    void customerSeesPendingForEveryUnfinishedStateAndAFinalAnswerOnlyForTerminalOnes() {
        for (TxnState state : TxnState.values()) {
            PayState shown = PayState.fromSwitchState(state.name());
            assertEquals(TxnStateMachine.isTerminal(state), shown != PayState.PENDING, state.name());
        }
    }

    // ---- config and HTTP ---------------------------------------------------------------

    @Test
    void bankCallTimeoutsComeFromConfig() {
        assertEquals(Duration.ofSeconds(2), properties.http().connectTimeout());
        assertEquals(Duration.ofSeconds(5), properties.http().readTimeout());
    }

    @Test
    void httpPayAndTxnLookup() throws Exception {
        ReqPay req = raviPaysAnu200();
        when(banks.debit(any(), any())).thenReturn(OK);
        when(banks.credit(any(), any())).thenReturn(OK);

        mvc.perform(post("/pay").contentType(MediaType.APPLICATION_JSON).content("""
                        {"txnId": "%s", "payerVpa": "ravi@banka", "payeeVpa": "anu@bankb", "amount": 20000}
                        """.formatted(req.txnId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.txnId").value(req.txnId()))
                .andExpect(jsonPath("$.state").value("SUCCESS"));

        mvc.perform(get("/txns/" + req.txnId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUCCESS"))
                .andExpect(jsonPath("$.payerBank").value("BANKA"))
                .andExpect(jsonPath("$.payeeBank").value("BANKB"))
                .andExpect(jsonPath("$.amount").value(20000))
                .andExpect(jsonPath("$.events.length()").value(5))
                .andExpect(jsonPath("$.events[0].toState").value("INITIATED"))
                .andExpect(jsonPath("$.events[4].fromState").value("CREDIT_SENT"))
                .andExpect(jsonPath("$.events[4].toState").value("SUCCESS"));
    }

    @Test
    void httpPayWithAnUnknownDebitAnswersPendingAndTheHistoryShowsWhy() throws Exception {
        ReqPay req = raviPaysAnu200();
        when(banks.debit(any(), any())).thenReturn(BankCallResult.unknown("timeout"));

        mvc.perform(post("/pay").contentType(MediaType.APPLICATION_JSON).content("""
                        {"txnId": "%s", "payerVpa": "ravi@banka", "payeeVpa": "anu@bankb", "amount": 20000}
                        """.formatted(req.txnId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PENDING"))
                .andExpect(jsonPath("$.reason").value("DEBIT_UNKNOWN: timeout"));

        mvc.perform(get("/txns/" + req.txnId()))
                .andExpect(jsonPath("$.state").value("DEBIT_SENT"))
                .andExpect(jsonPath("$.events[2].fromState").value("DEBIT_SENT"))
                .andExpect(jsonPath("$.events[2].toState").value("DEBIT_SENT"))
                .andExpect(jsonPath("$.events[2].reason").value("DEBIT_UNKNOWN: timeout"));
    }

    @Test
    void httpErrorsAreClear() throws Exception {
        mvc.perform(post("/pay").contentType(MediaType.APPLICATION_JSON).content("""
                        {"txnId": "t-bad-vpa", "payerVpa": "ravi", "payeeVpa": "anu@bankb", "amount": 20000}
                        """))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/pay").contentType(MediaType.APPLICATION_JSON).content("""
                        {"txnId": "t-zero", "payerVpa": "ravi@banka", "payeeVpa": "anu@bankb", "amount": 0}
                        """))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/txns/" + newTxnId()))
                .andExpect(status().isNotFound());
    }

    /** The states this payment passed through, in order. */
    private List<TxnState> path(String txnId) {
        return service.history(txnId).stream().map(TxnEvent::toState).toList();
    }

    private TxnEvent last(String txnId) {
        List<TxnEvent> events = service.history(txnId);
        return events.get(events.size() - 1);
    }
}

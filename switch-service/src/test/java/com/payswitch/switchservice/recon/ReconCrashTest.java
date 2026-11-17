package com.payswitch.switchservice.recon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payswitch.common.bank.DeclineReason;
import com.payswitch.common.bank.OpType;
import com.payswitch.common.bank.ReverseRequest;
import com.payswitch.switchservice.TestcontainersConfig;
import com.payswitch.switchservice.bank.BankCallResult;
import com.payswitch.switchservice.bank.BankClient;
import com.payswitch.switchservice.domain.Txn;
import com.payswitch.switchservice.domain.TxnState;
import com.payswitch.switchservice.service.PaymentFlow;
import com.payswitch.switchservice.service.TransactionService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Recon's own safety: a crash in the middle of a run, and banks that cannot be read.
 * Banks are mocked so the crash can be placed exactly where we want it.
 */
@SpringBootTest(properties = {
        "switch.resolution.enabled=false",          // the background resolver must not interfere
        "switch.retry.base-delay=10ms",
        "switch.retry.max-attempts=2",
        "switch.recon.enabled=false",
        "switch.recon.cutoff=0s",
        "switch.recon.run-lease=0s",                // a crashed run may be taken over at once
})
@Import(TestcontainersConfig.class)
class ReconCrashTest {

    @MockitoBean
    private BankClient banks;

    @MockitoBean
    private BankReconClient bankRecon;

    @MockitoSpyBean
    private PaymentFlow flow;

    @Autowired
    private ReconciliationService recon;

    @Autowired
    private TransactionService txns;

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void forgetScriptedCrash() {
        reset(flow);
    }

    private Instant dbNow() {
        return jdbc.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
    }

    /** Ravi debited, Anu's bank never answered: DEEMED_APPROVED. */
    private String deemedApproved() {
        String id = "TXN-" + UUID.randomUUID();
        Txn txn = txns.create(id, "ravi@banka", "anu@bankb", 20_000);
        for (TxnState s : List.of(TxnState.DEBIT_SENT, TxnState.DEBITED, TxnState.CREDIT_SENT, TxnState.DEEMED_APPROVED)) {
            txn = txns.transition(id, txn.version(), s, "test setup");
        }
        return id;
    }

    @Test
    void aRunThatCrashesHalfWayIsFinishedByTheNextRunAndNothingHappensTwice() {
        Instant from = dbNow();
        String first = deemedApproved();
        String second = deemedApproved();
        Instant to = dbNow().plusMillis(1);
        // Neither credit is in Bank B's records; when asked, Bank B fences; Bank A refunds.
        when(bankRecon.fetchAll(any(), any(), any(), anyInt())).thenReturn(List.of());
        when(banks.statusCheckAndFence(any(), anyString(), eq(OpType.CREDIT)))
                .thenReturn(BankCallResult.declined(DeclineReason.FENCED));
        when(banks.reverse(any(), any())).thenReturn(BankCallResult.success());
        // The very first resolution crashes the run (as if the process died right there).
        Mockito.doThrow(new IllegalStateException("simulated crash")).doCallRealMethod().when(flow).resolve(anyString());

        assertThrows(IllegalStateException.class, () -> recon.run(from, to));

        long runId = jdbc.sql("SELECT id FROM recon_runs WHERE window_from = :f")
                .param("f", OffsetDateTime.ofInstant(from, java.time.ZoneOffset.UTC)).query(Long.class).single();
        assertEquals("RUNNING", recon.report(runId).status());                 // left mid-way
        assertEquals(TxnState.DEEMED_APPROVED, txns.get(first).state());

        ReconReport rerun = recon.run(from, to);

        assertEquals("COMPLETED", rerun.status());
        assertEquals(2, rerun.counts().get("RESOLVED_DEEMED"));
        assertTrue(rerun.exceptions().isEmpty());
        assertEquals(TxnState.REVERSED, txns.get(first).state());
        assertEquals(TxnState.REVERSED, txns.get(second).state());
        verify(banks, times(1)).reverse(any(), eq(new ReverseRequest(first)));
        verify(banks, times(1)).reverse(any(), eq(new ReverseRequest(second)));

        // And once more: same window, same report, no new bank calls.
        ReconReport again = recon.run(from, to);
        assertEquals(rerun, again);
        verify(banks, times(1)).reverse(any(), eq(new ReverseRequest(first)));
        verify(banks, times(1)).reverse(any(), eq(new ReverseRequest(second)));
    }

    /** "I could not read Bank B" must never turn into "Bank B has no record". */
    @Test
    void ifABankCannotBeReadNothingIsDecided() {
        Instant from = dbNow();
        String id = deemedApproved();
        Instant to = dbNow().plusMillis(1);
        doCallRealMethod().when(flow).resolve(anyString());
        when(bankRecon.fetchAll(any(), any(), any(), anyInt()))
                .thenThrow(new ReconUnavailableException("Could not read BANKB recon entries"));

        assertThrows(ReconUnavailableException.class, () -> recon.run(from, to));

        assertEquals(TxnState.DEEMED_APPROVED, txns.get(id).state());
        verify(banks, never()).statusCheckAndFence(any(), anyString(), any());
        verify(banks, never()).reverse(any(), any());
    }
}

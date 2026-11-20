package com.payswitch.switchservice.recon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.payswitch.switchservice.TestcontainersConfig;
import com.payswitch.switchservice.domain.Txn;
import com.payswitch.switchservice.domain.TxnState;
import com.payswitch.switchservice.service.TransactionService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Bug found by the Phase 10 chaos test (the money auditor's check 6 failed on a 2,000 payment run).
 *
 * <p>Recon asked "which payments are old enough?" at the START of a run, and "how many were too
 * new?" at the END, each with its own now(). A payment that was too new at the start but old
 * enough by the end was in neither answer: never reconciled, while the run said COMPLETED. It was
 * then never settled, so SWITCH_SETTLEMENT never went back to 0.
 *
 * <p>Smallest case: one payment, a 1 s cutoff, and a run that takes 1.5 s between the two questions.
 */
@SpringBootTest(properties = {
        "switch.resolution.enabled=false",
        "switch.recon.enabled=false",
        "switch.recon.cutoff=1s",
})
@Import(TestcontainersConfig.class)
class ReconHorizonTest {

    @MockitoBean
    private BankReconClient bankRecon;

    @MockitoSpyBean
    private TransactionService txns;

    @Autowired
    private ReconciliationService recon;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void aPaymentTooNewWhenTheRunStartsIsReportedAsSkippedNotSilentlyLost() throws Exception {
        Instant from = dbNow();
        String id = "TXN-" + UUID.randomUUID();
        Txn txn = txns.create(id, "ravi@banka", "anu@bankb", 20_000);
        txns.transition(id, txn.version(), TxnState.FAILED, "test setup");     // nothing ever reached a bank
        Instant to = dbNow().plusMillis(1);
        when(bankRecon.fetchAll(any(), any(), any(), anyInt())).thenReturn(List.of());
        // The run is slow: by the time it finishes, the payment is older than the cutoff.
        doAnswer(call -> {
            Object result = call.callRealMethod();
            Thread.sleep(1_500);
            return result;
        }).when(txns).createdBetween(any(), any(), any());

        ReconReport first = recon.run(from, to);

        assertEquals("PARTIAL", first.status(), "a payment was skipped, so the window is not complete");
        assertEquals(1, first.counts().get("SKIPPED_TOO_NEW"));
        assertEquals(0, items(id));

        // Running the window again (as the auditor and the recon job do for PARTIAL) picks it up.
        ReconReport second = recon.run(from, to);

        assertEquals("COMPLETED", second.status());
        assertEquals(1, items(id));
        assertEquals(1, second.counts().get("MATCHED"));
    }

    private int items(String txnId) {
        return jdbc.sql("SELECT COUNT(*) FROM recon_items WHERE txn_id = :t").param("t", txnId)
                .query(Integer.class).single();
    }

    private Instant dbNow() {
        return jdbc.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
    }
}

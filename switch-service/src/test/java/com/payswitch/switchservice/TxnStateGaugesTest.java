package com.payswitch.switchservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.payswitch.switchservice.domain.Txn;
import com.payswitch.switchservice.domain.TxnState;
import com.payswitch.switchservice.metrics.SwitchMetrics;
import com.payswitch.switchservice.metrics.TxnStateGauges;
import com.payswitch.switchservice.recon.ReconCategory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Phase 11: the per-state gauges, and counters that exist (at 0) before anything happened. */
class TxnStateGaugesTest extends SwitchIntegrationTest {

    @Autowired
    private TxnStateGauges gauges;

    @Autowired
    private SwitchMetrics metrics;

    @Autowired
    private JdbcClient jdbc;

    private double gauge(String name, TxnState state) {
        return metrics.registry().get(name).tag("state", state.name()).gauge().value();
    }

    @Test
    void aPaymentQuietForLongerThanStuckAfterCountsAsStuck() {
        gauges.refresh();
        double inStateBefore = gauge("payswitch.txns.in.state", TxnState.CREDIT_SENT);
        double stuckBefore = gauge("payswitch.txns.stuck", TxnState.CREDIT_SENT);

        Txn fresh = paymentIn(TxnState.CREDIT_SENT);           // just moved: in flight, not stuck
        Txn old = paymentIn(TxnState.CREDIT_SENT);
        jdbc.sql("UPDATE transactions SET updated_at = now() - interval '1 hour' WHERE txn_id = :id")
                .param("id", old.txnId()).update();
        gauges.refresh();

        assertEquals(inStateBefore + 2, gauge("payswitch.txns.in.state", TxnState.CREDIT_SENT));
        assertEquals(stuckBefore + 1, gauge("payswitch.txns.stuck", TxnState.CREDIT_SENT));

        // Finishing them takes them out of both gauges.
        service.transition(fresh.txnId(), TxnState.SUCCESS, "test");
        service.transition(old.txnId(), TxnState.SUCCESS, "test");
        gauges.refresh();
        assertEquals(inStateBefore, gauge("payswitch.txns.in.state", TxnState.CREDIT_SENT));
        assertEquals(stuckBefore, gauge("payswitch.txns.stuck", TxnState.CREDIT_SENT));
    }

    @Test
    void rareCountersExistFromTheStartSoRateAndAlertsSeeTheirFirstIncrement() {
        for (ReconCategory category : ReconCategory.values()) {
            assertNotNull(metrics.registry().find("payswitch.recon.items").tag("category", category.name()).counter(),
                    category.name());
        }
        for (TxnState state : new TxnState[] {TxnState.SUCCESS, TxnState.FAILED, TxnState.REVERSED}) {
            assertNotNull(metrics.registry().find("payswitch.payments").tag("final_state", state.name()).counter(),
                    state.name());
        }
    }
}

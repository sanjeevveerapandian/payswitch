package com.payswitch.switchservice.settlement;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SettlementRepository {

    private final JdbcClient jdbc;

    public SettlementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Cycle(long id, String status, Instant openedAt, Instant closedAt, Instant settledAt,
                        String failureReason) {
    }

    /**
     * Only one close at a time, across every copy of the switch. Held until the transaction ends.
     * (A row lock on the OPEN cycle is not enough: a second closer waiting on it would wake up to
     * find the row no longer OPEN, and would not see the new open cycle in the same statement.)
     */
    public void lockForClosing() {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('settlement-close', 0))").query().singleRow();
    }

    /** The open cycle; opens one if there is none. */
    public Cycle openCycle() {
        return jdbc.sql("SELECT * FROM settlement_cycles WHERE status = 'OPEN'")
                .query(SettlementRepository::cycle).optional()
                .orElseGet(() -> cycle(openNewCycle()).orElseThrow());
    }

    public long openNewCycle() {
        return jdbc.sql("INSERT INTO settlement_cycles (status) VALUES ('OPEN') RETURNING id")
                .query(Long.class).single();
    }

    public Optional<Cycle> cycle(long id) {
        return jdbc.sql("SELECT * FROM settlement_cycles WHERE id = :id")
                .param("id", id).query(SettlementRepository::cycle).optional();
    }

    /** Reads the cycle and locks its row until the transaction ends. */
    public Optional<Cycle> lockCycle(long id) {
        return jdbc.sql("SELECT * FROM settlement_cycles WHERE id = :id FOR UPDATE")
                .param("id", id).query(SettlementRepository::cycle).optional();
    }

    public List<Long> closedCycles() {
        return jdbc.sql("SELECT id FROM settlement_cycles WHERE status = 'CLOSED' ORDER BY id")
                .query(Long.class).list();
    }

    /**
     * Puts every payment that is ready into this cycle. Ready means all of:
     * <ul>
     *   <li>the switch says SUCCESS (DEEMED_APPROVED is not final, so it waits);</li>
     *   <li>its LATEST recon line says the banks agree: MATCHED, or RESOLVED_DEEMED and really
     *       resolved. A recon exception waits until a later recon run says MATCHED;</li>
     *   <li>it is not already in a live cycle (settled, or about to be).</li>
     * </ul>
     *
     * @return how many payments joined
     */
    public int admitReconciled(long cycleId) {
        return jdbc.sql("""
                INSERT INTO settlement_items (cycle_id, txn_id, payer_bank, payee_bank, amount)
                SELECT :cycle, t.txn_id, t.payer_bank, t.payee_bank, t.amount
                FROM transactions t
                JOIN LATERAL (SELECT r.category, r.resolved_at FROM recon_items r
                              WHERE r.txn_id = t.txn_id ORDER BY r.id DESC LIMIT 1) latest ON TRUE
                WHERE t.state = 'SUCCESS'
                  AND (latest.category = 'MATCHED'
                       OR (latest.category = 'RESOLVED_DEEMED' AND latest.resolved_at IS NOT NULL))
                  AND NOT EXISTS (SELECT 1 FROM settlement_items s WHERE s.txn_id = t.txn_id AND s.active)
                """)
                .param("cycle", cycleId)
                .update();
    }

    public List<SettlementItem> items(long cycleId) {
        return jdbc.sql("""
                SELECT txn_id, payer_bank, payee_bank, amount FROM settlement_items
                WHERE cycle_id = :cycle ORDER BY txn_id
                """)
                .param("cycle", cycleId)
                .query((rs, n) -> new SettlementItem(rs.getString("txn_id"), rs.getString("payer_bank"),
                        rs.getString("payee_bank"), rs.getLong("amount")))
                .list();
    }

    public void savePositions(long cycleId, Map<String, Long> nets) {
        nets.forEach((bank, net) -> jdbc.sql("""
                INSERT INTO net_positions (cycle_id, bank_code, net_amount) VALUES (:cycle, :bank, :net)
                """)
                .param("cycle", cycleId).param("bank", bank).param("net", net)
                .update());
    }

    public List<SettlementReport.Position> positions(long cycleId) {
        return jdbc.sql("""
                SELECT bank_code, net_amount, advised_at IS NOT NULL AS advised FROM net_positions
                WHERE cycle_id = :cycle ORDER BY bank_code
                """)
                .param("cycle", cycleId)
                .query((rs, n) -> new SettlementReport.Position(rs.getString("bank_code"),
                        rs.getLong("net_amount"), rs.getBoolean("advised")))
                .list();
    }

    public void markAdvised(long cycleId, String bank) {
        jdbc.sql("""
                UPDATE net_positions SET advised_at = now()
                WHERE cycle_id = :cycle AND bank_code = :bank AND advised_at IS NULL
                """)
                .param("cycle", cycleId).param("bank", bank).update();
    }

    public void markClosed(long cycleId) {
        closeAs(cycleId, "CLOSED", null);
    }

    /** FAILED, and its payments are released so they can join the next cycle. */
    public void markFailed(long cycleId, String reason) {
        closeAs(cycleId, "FAILED", reason);
        jdbc.sql("UPDATE settlement_items SET active = FALSE WHERE cycle_id = :cycle")
                .param("cycle", cycleId).update();
    }

    /** @return false if the cycle was not CLOSED (e.g. someone else marked it SETTLED first) */
    public boolean markSettled(long cycleId) {
        return jdbc.sql("""
                UPDATE settlement_cycles SET status = 'SETTLED', settled_at = now()
                WHERE id = :id AND status = 'CLOSED'
                """)
                .param("id", cycleId).update() == 1;
    }

    private void closeAs(long cycleId, String status, String reason) {
        int updated = jdbc.sql("""
                UPDATE settlement_cycles SET status = :status, closed_at = now(), failure_reason = :reason
                WHERE id = :id AND status = 'OPEN'
                """)
                .param("status", status).param("reason", reason).param("id", cycleId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Cycle " + cycleId + " was not OPEN");
        }
    }

    private static Cycle cycle(ResultSet rs, int n) throws SQLException {
        return new Cycle(rs.getLong("id"), rs.getString("status"), instant(rs, "opened_at"),
                instant(rs, "closed_at"), instant(rs, "settled_at"), rs.getString("failure_reason"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}

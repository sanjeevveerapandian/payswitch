package com.payswitch.switchservice.db;

import com.payswitch.switchservice.domain.TxnEvent;
import com.payswitch.switchservice.domain.TxnState;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The audit trail. Only INSERT and SELECT exist here on purpose: history is never rewritten. */
@Repository
public class TxnEventRepository {

    private final JdbcClient jdbc;

    public TxnEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @param from null for the creation event */
    public void insert(String txnId, TxnState from, TxnState to, String reason) {
        jdbc.sql("""
                INSERT INTO txn_events (txn_id, from_state, to_state, reason)
                VALUES (:txnId, :from, :to, :reason)
                """)
                .param("txnId", txnId)
                .param("from", from == null ? null : from.name())
                .param("to", to.name())
                .param("reason", reason)
                .update();
    }

    /** Every event of one payment, oldest first. */
    public List<TxnEvent> findByTxnId(String txnId) {
        return jdbc.sql("""
                SELECT txn_id, from_state, to_state, reason, created_at
                FROM txn_events WHERE txn_id = :txnId ORDER BY id
                """)
                .param("txnId", txnId)
                .query((rs, n) -> new TxnEvent(
                        rs.getString("txn_id"),
                        rs.getString("from_state") == null ? null : TxnState.valueOf(rs.getString("from_state")),
                        TxnState.valueOf(rs.getString("to_state")),
                        rs.getString("reason"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }
}

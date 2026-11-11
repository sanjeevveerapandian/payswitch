package com.payswitch.bank.db;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payswitch.common.bank.BankResponse;
import com.payswitch.common.bank.OpType;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The idempotency table. One row per (txnId, op). The UNIQUE(txn_id, op_type) constraint in
 * Postgres is what makes "first caller wins" safe, even across many app instances.
 */
@Repository
public class ProcessedRequestRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ProcessedRequestRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Tries to become the one transaction that handles (txnId, op).
     *
     * <p>If another transaction already inserted the same key but has not committed yet,
     * Postgres makes this INSERT WAIT until that transaction commits or rolls back.
     *
     * @return true if we inserted the row (we must now do the work); false if the key exists
     */
    public boolean claim(String txnId, OpType op) {
        int inserted = jdbc.sql("""
                INSERT INTO processed_requests (txn_id, op_type, status)
                VALUES (:txnId, :op, 'PENDING')
                ON CONFLICT (txn_id, op_type) DO NOTHING
                """)
                .param("txnId", txnId)
                .param("op", op.name())
                .update();
        return inserted == 1;
    }

    /** Inserts a finished response only if no row exists yet. Returns true if inserted. */
    public boolean insertIfAbsent(BankResponse response) {
        int inserted = jdbc.sql("""
                INSERT INTO processed_requests (txn_id, op_type, status, response)
                VALUES (:txnId, :op, :status, :response)
                ON CONFLICT (txn_id, op_type) DO NOTHING
                """)
                .param("txnId", response.txnId())
                .param("op", response.op().name())
                .param("status", response.result().name())
                .param("response", toJson(response))
                .update();
        return inserted == 1;
    }

    /** Replaces our PENDING row with the final answer, in the same transaction as the money. */
    public void complete(BankResponse response) {
        int updated = jdbc.sql("""
                UPDATE processed_requests SET status = :status, response = :response
                WHERE txn_id = :txnId AND op_type = :op AND status = 'PENDING'
                """)
                .param("status", response.result().name())
                .param("response", toJson(response))
                .param("txnId", response.txnId())
                .param("op", response.op().name())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("No PENDING row for " + response.txnId() + "/" + response.op());
        }
    }

    /** The saved response for (txnId, op), if it exists and is finished. */
    public Optional<BankResponse> find(String txnId, OpType op) {
        return jdbc.sql("""
                SELECT response FROM processed_requests
                WHERE txn_id = :txnId AND op_type = :op AND status <> 'PENDING'
                """)
                .param("txnId", txnId)
                .param("op", op.name())
                .query((rs, n) -> fromJson(rs.getString("response")))
                .optional();
    }

    private String toJson(BankResponse response) {
        try {
            return json.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise response", e);
        }
    }

    private BankResponse fromJson(String text) {
        try {
            return json.readValue(text, BankResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read saved response", e);
        }
    }
}

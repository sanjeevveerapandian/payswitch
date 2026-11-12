package com.payswitch.switchservice.db;

import com.payswitch.switchservice.domain.Txn;
import com.payswitch.switchservice.domain.TxnState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TransactionRepository {

    private final JdbcClient jdbc;

    public TransactionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return false if a transaction with this txnId already exists (nothing is changed) */
    public boolean insert(String txnId, String payerVpa, String payeeVpa, String payerBank, String payeeBank,
                          long amount, TxnState state) {
        int inserted = jdbc.sql("""
                INSERT INTO transactions (txn_id, payer_vpa, payee_vpa, payer_bank, payee_bank, amount, state)
                VALUES (:txnId, :payerVpa, :payeeVpa, :payerBank, :payeeBank, :amount, :state)
                ON CONFLICT (txn_id) DO NOTHING
                """)
                .param("txnId", txnId)
                .param("payerVpa", payerVpa)
                .param("payeeVpa", payeeVpa)
                .param("payerBank", payerBank)
                .param("payeeBank", payeeBank)
                .param("amount", amount)
                .param("state", state.name())
                .update();
        return inserted == 1;
    }

    /** Plain read. No lock is taken: that is the point of optimistic locking. */
    public Optional<Txn> find(String txnId) {
        return jdbc.sql("SELECT * FROM transactions WHERE txn_id = :txnId")
                .param("txnId", txnId)
                .query(TransactionRepository::map)
                .optional();
    }

    /**
     * The optimistic lock. Changes the state ONLY IF the row still has the version we read.
     * If someone else changed the row in between, the version has moved on, the WHERE matches
     * nothing, and this returns 0.
     *
     * @return rows updated: 1 = we won, 0 = someone else got there first
     */
    public int updateState(String txnId, long expectedVersion, TxnState newState) {
        return jdbc.sql("""
                UPDATE transactions
                SET state = :state, version = version + 1, updated_at = now()
                WHERE txn_id = :txnId AND version = :expectedVersion
                """)
                .param("state", newState.name())
                .param("txnId", txnId)
                .param("expectedVersion", expectedVersion)
                .update();
    }

    private static Txn map(ResultSet rs, int rowNum) throws SQLException {
        return new Txn(
                rs.getString("txn_id"),
                rs.getString("payer_vpa"),
                rs.getString("payee_vpa"),
                rs.getString("payer_bank"),
                rs.getString("payee_bank"),
                rs.getLong("amount"),
                TxnState.valueOf(rs.getString("state")),
                rs.getLong("version"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }
}

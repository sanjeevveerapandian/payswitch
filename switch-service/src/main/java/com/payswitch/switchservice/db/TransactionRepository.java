package com.payswitch.switchservice.db;

import com.payswitch.switchservice.domain.Txn;
import com.payswitch.switchservice.domain.TxnState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

    /**
     * Same optimistic lock, but the state stays as it is. Used when something worth recording
     * happened (e.g. a bank call came back UNKNOWN) without a state change.
     *
     * @return rows updated: 1 = we won, 0 = someone else got there first
     */
    public int touch(String txnId, long expectedVersion) {
        return jdbc.sql("""
                UPDATE transactions
                SET version = version + 1, updated_at = now()
                WHERE txn_id = :txnId AND version = :expectedVersion
                """)
                .param("txnId", txnId)
                .param("expectedVersion", expectedVersion)
                .update();
    }

    /**
     * Claims up to {@code batchSize} stuck payments for one resolver. MUST run inside a database
     * transaction: the row locks from FOR UPDATE are what keep two resolvers apart.
     *
     * <p>SKIP LOCKED: if another resolver is claiming some of these rows right now, do not wait
     * for it. Just skip those rows and take the next ones. Two resolvers running at once end up
     * with different payments instead of queueing behind each other.
     *
     * <p>Then each claimed row gets a lease (next_attempt_at in the future), so after we commit,
     * other resolvers keep skipping it while we work on it.
     */
    public List<String> claimStuck(Collection<TxnState> states, Duration stuckFor, int batchSize, Duration lease) {
        List<String> ids = jdbc.sql("""
                SELECT txn_id FROM transactions
                WHERE state IN (:states)
                  AND updated_at <= now() - make_interval(secs => :stuckFor)
                  AND next_attempt_at <= now()
                ORDER BY updated_at
                LIMIT :batchSize
                FOR UPDATE SKIP LOCKED
                """)
                .param("states", states.stream().map(Enum::name).toList())
                .param("stuckFor", stuckFor.toMillis() / 1000.0)
                .param("batchSize", batchSize)
                .query(String.class)
                .list();
        if (!ids.isEmpty()) {
            jdbc.sql("UPDATE transactions SET next_attempt_at = now() + make_interval(secs => :lease) "
                            + "WHERE txn_id IN (:ids)")
                    .param("lease", lease.toMillis() / 1000.0)
                    .param("ids", ids)
                    .update();
        }
        return ids;
    }

    /** Leases one payment, e.g. while POST /pay is resolving it inline, so the job leaves it alone. */
    public void lease(String txnId, Duration lease) {
        jdbc.sql("UPDATE transactions SET next_attempt_at = now() + make_interval(secs => :lease) "
                        + "WHERE txn_id = :txnId")
                .param("lease", lease.toMillis() / 1000.0)
                .param("txnId", txnId)
                .update();
    }

    /**
     * Payments created in [from, to) and before {@code youngest} (a database-clock instant, the
     * same clock that stamped created_at), i.e. old enough to reconcile. Oldest first.
     */
    public List<Txn> createdBetween(Instant from, Instant to, Instant youngest) {
        return jdbc.sql("""
                SELECT * FROM transactions
                WHERE created_at >= :from AND created_at < :to
                  AND created_at < :youngest
                ORDER BY created_at, txn_id
                """)
                .param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .param("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .param("youngest", OffsetDateTime.ofInstant(youngest, ZoneOffset.UTC))
                .query(TransactionRepository::map)
                .list();
    }

    /**
     * How many payments in [from, to) were created at or after {@code youngest}. Must be given the
     * SAME {@code youngest} as {@link #createdBetween}: together the two answers cover every payment
     * in the window exactly once.
     */
    public int countTooRecent(Instant from, Instant to, Instant youngest) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM transactions
                WHERE created_at >= :from AND created_at < :to
                  AND created_at >= :youngest
                """)
                .param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .param("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .param("youngest", OffsetDateTime.ofInstant(youngest, ZoneOffset.UTC))
                .query(Integer.class)
                .single();
    }

    /** The database's clock, the same clock that stamps created_at. */
    public Instant dbNow() {
        return jdbc.sql("SELECT clock_timestamp()").query(OffsetDateTime.class).single().toInstant();
    }

    /** The newest {@code limit} payments, newest first (for the live monitor's snapshot). */
    public List<Txn> latest(int limit) {
        return jdbc.sql("SELECT * FROM transactions ORDER BY created_at DESC, txn_id LIMIT :limit")
                .param("limit", limit)
                .query(TransactionRepository::map)
                .list();
    }

    /** How many payments are in each state right now (all states, including final ones). */
    public java.util.Map<TxnState, Long> countByState() {
        java.util.Map<TxnState, Long> counts = new java.util.EnumMap<>(TxnState.class);
        jdbc.sql("SELECT state, COUNT(*) AS n FROM transactions GROUP BY state")
                .query((rs, n) -> counts.put(TxnState.valueOf(rs.getString("state")), rs.getLong("n")))
                .list();
        return counts;
    }

    /** Which of these txnIds the switch knows at all (whatever their state or age). */
    public Set<String> existing(Collection<String> txnIds) {
        if (txnIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("SELECT txn_id FROM transactions WHERE txn_id IN (:ids)")
                .param("ids", txnIds)
                .query(String.class)
                .list());
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

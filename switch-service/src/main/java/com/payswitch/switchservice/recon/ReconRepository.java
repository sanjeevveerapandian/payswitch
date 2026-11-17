package com.payswitch.switchservice.recon;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReconRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public ReconRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public record Run(long id, Instant windowFrom, Instant windowTo, String status, Map<String, Integer> counts) {
    }

    /** The run for this exact window, created if it does not exist yet. */
    public long findOrCreateRun(Instant from, Instant to) {
        jdbc.sql("""
                INSERT INTO recon_runs (window_from, window_to, status) VALUES (:from, :to, 'NEW')
                ON CONFLICT (window_from, window_to) DO NOTHING
                """)
                .param("from", utc(from))
                .param("to", utc(to))
                .update();
        return jdbc.sql("SELECT id FROM recon_runs WHERE window_from = :from AND window_to = :to")
                .param("from", utc(from))
                .param("to", utc(to))
                .query(Long.class)
                .single();
    }

    /**
     * Takes the run for ourselves, unless someone else is running it right now (status RUNNING with
     * a lease that has not run out). A crashed runner's lease runs out, so its run can be taken over.
     */
    public boolean claim(long runId, Duration lease) {
        return jdbc.sql("""
                UPDATE recon_runs
                SET status = 'RUNNING', started_at = now(), finished_at = NULL,
                    lease_until = now() + make_interval(secs => :lease)
                WHERE id = :id AND (status <> 'RUNNING' OR lease_until < now())
                """)
                .param("lease", lease.toMillis() / 1000.0)
                .param("id", runId)
                .update() == 1;
    }

    public void finish(long runId, String status, Map<String, Integer> counts) {
        jdbc.sql("""
                UPDATE recon_runs SET status = :status, finished_at = now(), lease_until = NULL,
                                      counts = CAST(:counts AS jsonb)
                WHERE id = :id
                """)
                .param("status", status)
                .param("counts", toJson(counts))
                .param("id", runId)
                .update();
    }

    public Optional<Run> run(long runId) {
        return jdbc.sql("SELECT id, window_from, window_to, status, counts::text AS counts FROM recon_runs WHERE id = :id")
                .param("id", runId)
                .query((rs, n) -> new Run(
                        rs.getLong("id"),
                        rs.getObject("window_from", OffsetDateTime.class).toInstant(),
                        rs.getObject("window_to", OffsetDateTime.class).toInstant(),
                        rs.getString("status"),
                        fromJson(rs.getString("counts"))))
                .optional();
    }

    /** @return false if this run already has a line for this txnId (nothing is changed) */
    public boolean insertItem(long runId, String txnId, ReconCategory category, String switchState,
                              String bankView, String action) {
        return jdbc.sql("""
                INSERT INTO recon_items (run_id, txn_id, category, switch_state, bank_view, action_taken)
                VALUES (:runId, :txnId, :category, :state, :view, :action)
                ON CONFLICT (run_id, txn_id) DO NOTHING
                """)
                .param("runId", runId)
                .param("txnId", txnId)
                .param("category", category.name())
                .param("state", switchState)
                .param("view", bankView)
                .param("action", action)
                .update() == 1;
    }

    /** Records how a DEEMED_APPROVED line ended. resolvedAt is set only when it really was resolved. */
    public void finishItem(long runId, String txnId, ReconCategory category, String switchState, String action,
                           boolean resolved) {
        jdbc.sql("""
                UPDATE recon_items
                SET category = :category, switch_state = :state, action_taken = :action,
                    resolved_at = CASE WHEN :resolved THEN now() ELSE NULL END
                WHERE run_id = :runId AND txn_id = :txnId
                """)
                .param("category", category.name())
                .param("state", switchState)
                .param("action", action)
                .param("resolved", resolved)
                .param("runId", runId)
                .param("txnId", txnId)
                .update();
    }

    public Set<String> itemTxnIds(long runId) {
        return new HashSet<>(jdbc.sql("SELECT txn_id FROM recon_items WHERE run_id = :runId")
                .param("runId", runId).query(String.class).list());
    }

    /** DEEMED_APPROVED lines a crashed run started but did not finish. */
    public List<String> deemedInProgress(long runId) {
        return jdbc.sql("""
                SELECT txn_id FROM recon_items
                WHERE run_id = :runId AND category = 'RESOLVED_DEEMED' AND resolved_at IS NULL
                ORDER BY id
                """)
                .param("runId", runId).query(String.class).list();
    }

    /** Every category with its count (zeros included), in a fixed order. */
    public Map<String, Integer> counts(long runId) {
        Map<String, Integer> counts = new TreeMap<>();
        for (ReconCategory c : ReconCategory.values()) {
            counts.put(c.name(), 0);
        }
        jdbc.sql("SELECT category, COUNT(*) AS n FROM recon_items WHERE run_id = :runId GROUP BY category")
                .param("runId", runId)
                .query(rs -> {
                    counts.put(rs.getString("category"), rs.getInt("n"));
                });
        return counts;
    }

    public List<ReconReport.Item> exceptions(long runId) {
        List<String> exceptional = java.util.Arrays.stream(ReconCategory.values())
                .filter(ReconCategory::isException).map(Enum::name).toList();
        return jdbc.sql("""
                SELECT txn_id, category, switch_state, bank_view, action_taken FROM recon_items
                WHERE run_id = :runId AND category IN (:categories)
                ORDER BY txn_id
                """)
                .param("runId", runId)
                .param("categories", exceptional)
                .query((rs, n) -> new ReconReport.Item(rs.getString("txn_id"), rs.getString("category"),
                        rs.getString("switch_state"), rs.getString("bank_view"), rs.getString("action_taken")))
                .list();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private String toJson(Map<String, Integer> counts) {
        try {
            return json.writeValueAsString(counts);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Integer> fromJson(String text) {
        if (text == null) {
            return Map.of();
        }
        try {
            return new TreeMap<>(json.readValue(text, new TypeReference<Map<String, Integer>>() { }));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}

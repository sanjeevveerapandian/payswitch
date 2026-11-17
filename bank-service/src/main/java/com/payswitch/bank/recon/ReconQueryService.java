package com.payswitch.bank.recon;

import com.payswitch.common.bank.OpType;
import com.payswitch.common.bank.ReconEntry;
import com.payswitch.common.bank.ReconPage;
import com.payswitch.common.bank.ReconStatus;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only: every switch operation this bank has a final answer for, in a time window.
 * Built from the idempotency table (what was asked, what we answered) and the journal (how much
 * actually moved). In-flight requests (still PENDING) are left out.
 */
@Service
public class ReconQueryService {

    public static final int MAX_PAGE_SIZE = 1_000;

    private final JdbcClient jdbc;

    public ReconQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param from inclusive
     * @param to   exclusive
     */
    @Transactional(readOnly = true)
    public ReconPage entries(Instant from, Instant to, int page, int size) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page must be >= 0 and size between 1 and " + MAX_PAGE_SIZE);
        }
        // Ask for one row more than the page: if it comes back, there is another page.
        List<ReconEntry> rows = jdbc.sql("""
                SELECT p.txn_id, p.op_type, p.status, p.created_at,
                       p.response::jsonb ->> 'reason' AS reason,
                       COALESCE((SELECT SUM(l.amount)
                                 FROM journal_entries e JOIN entry_lines l ON l.journal_entry_id = e.id
                                 WHERE e.txn_id = p.txn_id AND e.op_type = p.op_type AND l.amount > 0), 0) AS amount
                FROM processed_requests p
                WHERE p.status <> 'PENDING'
                  AND p.created_at >= :from AND p.created_at < :to
                ORDER BY p.created_at, p.txn_id, p.op_type
                LIMIT :limit OFFSET :offset
                """)
                .param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .param("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .param("limit", size + 1)
                .param("offset", (long) page * size)
                .query((rs, n) -> new ReconEntry(
                        rs.getString("txn_id"),
                        OpType.valueOf(rs.getString("op_type")),
                        status(rs.getString("status"), rs.getString("reason")),
                        rs.getLong("amount"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
        boolean hasMore = rows.size() > size;
        return new ReconPage(hasMore ? rows.subList(0, size) : rows, page, size, hasMore);
    }

    private static ReconStatus status(String dbStatus, String reason) {
        return switch (dbStatus) {
            case "SUCCESS" -> ReconStatus.SUCCESS;
            case "NO_DEBIT" -> ReconStatus.NO_DEBIT;
            // Tombstones: a fencing status check, or a reverse that blocked a late debit.
            default -> "FENCED".equals(reason) || "ALREADY_REVERSED".equals(reason)
                    ? ReconStatus.FENCED : ReconStatus.DECLINED;
        };
    }
}

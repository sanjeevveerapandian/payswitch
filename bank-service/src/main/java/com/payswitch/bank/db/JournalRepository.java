package com.payswitch.bank.db;

import com.payswitch.ledger.EntryLine;
import com.payswitch.ledger.JournalEntry;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Writes and reads journal_entries + entry_lines. Entries are never updated or deleted. */
@Repository
public class JournalRepository {

    private final JdbcClient jdbc;

    public JournalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(JournalEntry entry, String opType) {
        jdbc.sql("""
                INSERT INTO journal_entries (id, txn_id, op_type, description)
                VALUES (:id, :txnId, :opType, :description)
                """)
                .param("id", entry.id())
                .param("txnId", entry.txnId())
                .param("opType", opType)
                .param("description", entry.description())
                .update();

        for (EntryLine line : entry.lines()) {
            jdbc.sql("""
                    INSERT INTO entry_lines (journal_entry_id, account_id, amount)
                    VALUES (:entryId, :accountId, :amount)
                    """)
                    .param("entryId", entry.id())
                    .param("accountId", line.accountId())
                    .param("amount", line.amount())
                    .update();
        }
    }

    /** Lines of the entry for (txnId, opType), in the order they were written. Empty if none. */
    public List<EntryLine> findLines(String txnId, String opType) {
        return jdbc.sql("""
                SELECT l.account_id, l.amount
                FROM entry_lines l
                JOIN journal_entries e ON e.id = l.journal_entry_id
                WHERE e.txn_id = :txnId AND e.op_type = :opType
                ORDER BY l.id
                """)
                .param("txnId", txnId)
                .param("opType", opType)
                .query((rs, n) -> new EntryLine(rs.getString("account_id"), rs.getLong("amount")))
                .list();
    }
}

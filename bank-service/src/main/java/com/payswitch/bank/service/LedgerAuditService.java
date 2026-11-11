package com.payswitch.bank.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The same invariants as ledger-core's LedgerInvariants, checked with SQL over the real tables.
 * A bank would run this as a nightly job; our tests run it after every scenario.
 */
@Service
public class LedgerAuditService {

    private final JdbcClient jdbc;

    public LedgerAuditService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @throws IllegalStateException listing every broken rule */
    @Transactional(readOnly = true)
    public void check() {
        List<String> problems = new ArrayList<>();

        // 1. Money is never created or destroyed: all raw balances sum to 0.
        long total = jdbc.sql("SELECT COALESCE(SUM(raw_balance), 0) FROM accounts").query(Long.class).single();
        if (total != 0) {
            problems.add("sum of raw balances is " + total);
        }

        // 2. No customer is below zero.
        problems.addAll(jdbc.sql("""
                SELECT id FROM accounts
                WHERE NOT allow_negative
                  AND ((type = 'LIABILITY' AND raw_balance > 0) OR (type = 'ASSET' AND raw_balance < 0))
                """).query(String.class).list().stream().map(id -> "negative account " + id).toList());

        // 3. Every journal entry has 2+ lines summing to 0.
        problems.addAll(jdbc.sql("""
                SELECT journal_entry_id FROM entry_lines
                GROUP BY journal_entry_id HAVING SUM(amount) <> 0 OR COUNT(*) < 2
                """).query(String.class).list().stream().map(id -> "unbalanced entry " + id).toList());

        // 4. Each stored balance equals the sum of that account's lines (replaying the journal).
        problems.addAll(jdbc.sql("""
                SELECT a.id FROM accounts a
                LEFT JOIN (SELECT account_id, SUM(amount) AS total FROM entry_lines GROUP BY account_id) l
                       ON l.account_id = a.id
                WHERE a.raw_balance <> COALESCE(l.total, 0)
                """).query(String.class).list().stream().map(id -> "balance disagrees with journal: " + id).toList());

        if (!problems.isEmpty()) {
            throw new IllegalStateException("Ledger audit failed: " + problems);
        }
    }
}

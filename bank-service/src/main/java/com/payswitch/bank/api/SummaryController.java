package com.payswitch.bank.api;

import com.payswitch.bank.config.BankProperties;
import com.payswitch.common.bank.BankSummary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /admin/summary: this bank's money in three numbers, for the demo UI's TOTAL MONEY header.
 *
 * <p>ONE query, so all three numbers come from the same instant: a payment can never be half in
 * one and half in another. Raw balances are debit-positive; customer and SWITCH_SETTLEMENT accounts
 * are liabilities, so their natural balance is minus the raw one.
 */
@RestController
public class SummaryController {

    private final JdbcClient jdbc;
    private final BankProperties bank;

    public SummaryController(JdbcClient jdbc, BankProperties bank) {
        this.jdbc = jdbc;
        this.bank = bank;
    }

    @GetMapping("/admin/summary")
    @Transactional(readOnly = true)
    public BankSummary summary() {
        return jdbc.sql("""
                SELECT COALESCE(SUM(-raw_balance) FILTER (WHERE vpa IS NOT NULL), 0)             AS customers,
                       COUNT(*) FILTER (WHERE vpa IS NOT NULL)                                    AS accounts,
                       COALESCE(SUM(-raw_balance) FILTER (WHERE id = 'SWITCH_SETTLEMENT'), 0)    AS settlement,
                       COALESCE(SUM(raw_balance)  FILTER (WHERE id = 'CENTRAL_BANK_RESERVE'), 0) AS reserve
                FROM accounts
                """)
                .query((rs, n) -> new BankSummary(bank.code(), rs.getLong("customers"), rs.getLong("settlement"),
                        rs.getLong("reserve"), rs.getInt("accounts")))
                .single();
    }
}

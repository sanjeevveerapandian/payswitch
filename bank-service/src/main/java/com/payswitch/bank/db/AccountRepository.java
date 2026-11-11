package com.payswitch.bank.db;

import com.payswitch.ledger.AccountType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AccountRepository {

    private static final String COLUMNS = "id, vpa, name, type, allow_negative, raw_balance";

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Plain read, no lock. Fine for "does this VPA exist?" and for showing a balance. */
    public Optional<AccountRow> findByVpa(String vpa) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE vpa = :vpa")
                .param("vpa", vpa)
                .query(AccountRepository::map)
                .optional();
    }

    /**
     * Reads the row AND locks it until the current transaction ends. Any other transaction
     * that tries to lock the same row waits here. This is what stops two payments from both
     * reading "Rs 5,000" and both spending it.
     */
    public AccountRow lockById(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(AccountRepository::map)
                .optional()
                .orElseThrow(() -> new IllegalStateException("Account row missing: " + id));
    }

    public void insert(AccountRow row) {
        jdbc.sql("""
                INSERT INTO accounts (id, vpa, name, type, allow_negative, raw_balance)
                VALUES (:id, :vpa, :name, :type, :allowNegative, :rawBalance)
                """)
                .param("id", row.id())
                .param("vpa", row.vpa())
                .param("name", row.name())
                .param("type", row.type().name())
                .param("allowNegative", row.allowNegative())
                .param("rawBalance", row.rawBalance())
                .update();
    }

    public void updateRawBalance(String id, long rawBalance) {
        int updated = jdbc.sql("UPDATE accounts SET raw_balance = :raw WHERE id = :id")
                .param("raw", rawBalance)
                .param("id", id)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Expected to update 1 account row, updated " + updated);
        }
    }

    private static AccountRow map(ResultSet rs, int rowNum) throws SQLException {
        return new AccountRow(
                rs.getString("id"),
                rs.getString("vpa"),
                rs.getString("name"),
                AccountType.valueOf(rs.getString("type")),
                rs.getBoolean("allow_negative"),
                rs.getLong("raw_balance"));
    }
}

package com.payswitch.bank;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The safety nets must be shown to catch something, or they might be passing for the wrong reason
 * (review finding #12, and the uncovered lines JaCoCo found).
 *
 * <p>Each test breaks the books ON PURPOSE inside a database transaction that is always rolled
 * back, so no other test (same database) ever sees the damage.
 */
class LedgerAuditFailsTest extends BankIntegrationTest {

    @Autowired
    private TransactionTemplate tx;

    /** Runs the damage and the check together, then throws everything away. */
    private void insideRolledBackTransaction(Runnable damageThenCheck) {
        tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            damageThenCheck.run();
        });
    }

    @Test
    void auditCatchesMoneyCreatedOutOfNothing() {
        insideRolledBackTransaction(() -> {
            // One paisa appears in BANK_CASH with no journal entry behind it.
            jdbc.sql("UPDATE accounts SET raw_balance = raw_balance + 1 WHERE id = 'BANK_CASH'").update();

            IllegalStateException e = assertThrows(IllegalStateException.class, () -> audit.check());

            assertTrue(e.getMessage().contains("sum of raw balances is 1"), e.getMessage());
            assertTrue(e.getMessage().contains("balance disagrees with journal: BANK_CASH"), e.getMessage());
        });
    }

    @Test
    void auditCatchesAOneSidedJournalEntry() {
        insideRolledBackTransaction(() -> {
            jdbc.sql("INSERT INTO journal_entries (id, txn_id, op_type, description) "
                    + "VALUES ('BROKEN-1', 'BROKEN-1', 'TEST', 'one line only')").update();
            jdbc.sql("INSERT INTO entry_lines (journal_entry_id, account_id, amount) "
                    + "VALUES ('BROKEN-1', 'BANK_CASH', 500)").update();

            IllegalStateException e = assertThrows(IllegalStateException.class, () -> audit.check());

            assertTrue(e.getMessage().contains("unbalanced entry BROKEN-1"), e.getMessage());
        });
    }

    /** The audit's "negative account" rule can never even be reached: the database refuses first. */
    @Test
    void theDatabaseRefusesToMakeACustomerNegative() {
        String ravi = newCustomer(10_000);

        insideRolledBackTransaction(() -> assertThrows(DataIntegrityViolationException.class, () ->
                jdbc.sql("UPDATE accounts SET raw_balance = 1 WHERE vpa = :vpa").param("vpa", ravi).update()));
    }
}

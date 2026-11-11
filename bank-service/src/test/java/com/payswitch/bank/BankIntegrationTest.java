package com.payswitch.bank;

import com.payswitch.bank.service.AccountOpeningService;
import com.payswitch.bank.service.BankOperationsService;
import com.payswitch.bank.service.LedgerAuditService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Base class for Bank A tests. Every subclass uses the same Spring context, so Spring starts the
 * app and the Postgres container ONCE for all of them.
 *
 * <p>Tests share one database, so each test makes its own fresh customers and txnIds (random
 * UUIDs) instead of relying on a clean table.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "bank.code=BANKA")
@Import(TestcontainersConfig.class)
abstract class BankIntegrationTest {

    @Autowired
    protected BankOperationsService bank;

    @Autowired
    protected AccountOpeningService opening;

    @Autowired
    protected LedgerAuditService audit;

    @Autowired
    protected JdbcClient jdbc;

    /** After every test, the whole bank's books must still balance. */
    @AfterEach
    void booksStillBalance() {
        audit.check();
    }

    /** Opens a new customer with this many paise and returns their VPA. */
    protected String newCustomer(long openingPaise) {
        String vpa = "c" + UUID.randomUUID().toString().substring(0, 8) + "@banka";
        opening.openCustomer(vpa, "Test customer", openingPaise);
        return vpa;
    }

    protected static String newTxnId() {
        return "TXN-" + UUID.randomUUID();
    }

    protected long balance(String vpa) {
        return bank.balance(vpa).balance();
    }

    /** What the bank owes the switch, in paise (natural balance of a LIABILITY = -raw). */
    protected long settlement() {
        return -jdbc.sql("SELECT raw_balance FROM accounts WHERE id = 'SWITCH_SETTLEMENT'")
                .query(Long.class).single();
    }

    protected int journalEntriesFor(String txnId) {
        return jdbc.sql("SELECT COUNT(*) FROM journal_entries WHERE txn_id = :t")
                .param("t", txnId).query(Integer.class).single();
    }
}

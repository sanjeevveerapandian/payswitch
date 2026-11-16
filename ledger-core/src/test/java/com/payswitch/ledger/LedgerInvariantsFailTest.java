package com.payswitch.ledger;

import static com.payswitch.ledger.EntryLine.credit;
import static com.payswitch.ledger.EntryLine.debit;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * LedgerInvariants must catch a broken ledger, or every test that calls it proves nothing. The
 * Ledger's own API refuses to break itself, so these tests reach inside with reflection, which is
 * how a real bug (or a bad manual fix in production) would break it.
 */
class LedgerInvariantsFailTest {

    private Ledger ledger;

    @BeforeEach
    void ravisDeposit() {
        ledger = new Ledger();
        ledger.openAccount(new Account("CASH", "Bank cash", AccountType.ASSET, false));
        ledger.openAccount(new Account("RAVI", "Ravi", AccountType.LIABILITY, false));
        ledger.post(new JournalEntry("je-1", "t-1", "deposit", List.of(debit("CASH", 100_000), credit("RAVI", 100_000))));
        LedgerInvariants.check(ledger);                               // healthy to start with
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> rawBalancesInside() throws Exception {
        Field f = Ledger.class.getDeclaredField("rawBalances");
        f.setAccessible(true);
        return (Map<String, Long>) f.get(ledger);
    }

    @Test
    void catchesMoneyCreatedOutOfNothing() throws Exception {
        rawBalancesInside().put("CASH", 100_001L);                    // one paisa from nowhere

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> LedgerInvariants.check(ledger));

        assertTrue(e.getMessage().contains("Sum of all raw balances is 1"), e.getMessage());
    }

    @Test
    void catchesABalanceThatDisagreesWithTheJournal() throws Exception {
        // Totals still add up to zero, but the numbers no longer match what the journal says.
        rawBalancesInside().put("CASH", 90_000L);
        rawBalancesInside().put("RAVI", -90_000L);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> LedgerInvariants.check(ledger));

        assertTrue(e.getMessage().contains("but the journal says"), e.getMessage());
    }

    @Test
    void catchesACustomerBelowZero() throws Exception {
        rawBalancesInside().put("RAVI", 5_000L);                      // Ravi at -Rs 50
        rawBalancesInside().put("CASH", -5_000L);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> LedgerInvariants.check(ledger));

        assertTrue(e.getMessage().contains("No-negative account is negative"), e.getMessage());
    }
}

package com.payswitch.ledger;

import static com.payswitch.ledger.EntryLine.credit;
import static com.payswitch.ledger.EntryLine.debit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LedgerTest {

    private Ledger ledger;

    @BeforeEach
    void setUp() {
        ledger = new Ledger();
        ledger.openAccount(new Account("CASH", "Bank cash", AccountType.ASSET, false));
        ledger.openAccount(new Account("RAVI", "Ravi savings", AccountType.LIABILITY, false));
        ledger.openAccount(new Account("ANU", "Anu's shop current", AccountType.LIABILITY, false));
    }

    /** Ravi walks in and deposits Rs 1000 in cash. */
    private void raviDeposits1000() {
        ledger.post(new JournalEntry("je-dep", "txn-dep", "Ravi cash deposit",
                List.of(debit("CASH", 100_000), credit("RAVI", 100_000))));
    }

    @Test
    void balancedEntryPostsAndBalancesAreCorrect() {
        raviDeposits1000();

        assertEquals(100_000, ledger.balance("CASH"));   // bank has Rs 1000 more cash
        assertEquals(100_000, ledger.balance("RAVI"));   // bank owes Ravi Rs 1000
        assertEquals(0, ledger.balance("ANU"));
        assertEquals(-100_000, ledger.rawBalance("RAVI")); // stored as a credit internally
        assertEquals(1, ledger.allEntries().size());
        LedgerInvariants.check(ledger);
    }

    @Test
    void raviPaysAnu200InsideSameBank() {
        raviDeposits1000();
        long raviBefore = ledger.balance("RAVI");
        long anuBefore = ledger.balance("ANU");
        long cashBefore = ledger.balance("CASH");

        ledger.post(new JournalEntry("je-pay", "txn-200", "Ravi pays Anu Rs 200",
                List.of(debit("RAVI", 20_000), credit("ANU", 20_000))));

        assertEquals(-20_000, ledger.balance("RAVI") - raviBefore);
        assertEquals(+20_000, ledger.balance("ANU") - anuBefore);
        assertEquals(0, ledger.balance("CASH") - cashBefore); // no cash left the bank
        assertEquals(80_000, ledger.balance("RAVI"));
        assertEquals(20_000, ledger.balance("ANU"));
        LedgerInvariants.check(ledger);
    }

    @Test
    void overdraftIsRejectedAndNothingChanges() {
        raviDeposits1000();
        List<JournalEntry> entriesBefore = ledger.allEntries();

        // Ravi has Rs 1000 and tries to pay Rs 1000.01.
        JournalEntry tooMuch = new JournalEntry("je-big", "txn-big", "overdraft attempt",
                List.of(credit("ANU", 100_001), debit("RAVI", 100_001)));

        InsufficientFundsException e =
                assertThrows(InsufficientFundsException.class, () -> ledger.post(tooMuch));

        assertEquals("RAVI", e.accountId());
        assertEquals(100_000, ledger.balance("RAVI"));
        assertEquals(0, ledger.balance("ANU")); // ANU's line came first and was still NOT applied
        assertEquals(100_000, ledger.balance("CASH"));
        assertEquals(entriesBefore, ledger.allEntries());
        LedgerInvariants.check(ledger);
    }

    @Test
    void sameAccountOnTwoLinesIsCheckedOnItsNetChange() {
        raviDeposits1000();

        // Two debits of Rs 600 each: each alone fits in Rs 1000, together they do not.
        JournalEntry split = new JournalEntry("je-split", "txn-split", "split overdraft",
                List.of(debit("RAVI", 60_000), debit("RAVI", 60_000), credit("ANU", 120_000)));

        assertThrows(InsufficientFundsException.class, () -> ledger.post(split));
        assertEquals(100_000, ledger.balance("RAVI"));
        assertEquals(0, ledger.balance("ANU"));
    }

    @Test
    void unknownAccountIsRejectedAndNothingChanges() {
        raviDeposits1000();

        JournalEntry ghost = new JournalEntry("je-ghost", "txn-ghost", "to nobody",
                List.of(debit("RAVI", 20_000), credit("GHOST", 20_000)));

        assertThrows(IllegalArgumentException.class, () -> ledger.post(ghost));
        assertEquals(100_000, ledger.balance("RAVI"));
        assertEquals(1, ledger.allEntries().size());
    }

    @Test
    void openingTheSameAccountTwiceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ledger.openAccount(
                new Account("RAVI", "duplicate", AccountType.LIABILITY, false)));
    }

    @Test
    void freshLedgerPassesInvariants() {
        LedgerInvariants.check(ledger);
    }
}

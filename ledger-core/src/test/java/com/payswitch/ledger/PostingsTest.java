package com.payswitch.ledger;

import static com.payswitch.ledger.EntryLine.credit;
import static com.payswitch.ledger.EntryLine.debit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PostingsTest {

    private final Map<String, Account> accounts = Map.of(
            "RAVI", new Account("RAVI", "Ravi", AccountType.LIABILITY, false),
            "SETTLE", new Account("SETTLE", "Switch settlement", AccountType.LIABILITY, true));

    @Test
    void returnsNewBalancesAndDoesNotTouchTheInput() {
        Map<String, Long> raw = new HashMap<>(Map.of("RAVI", -100_000L, "SETTLE", 0L));
        JournalEntry pay = new JournalEntry("je-1", "txn-1", "Ravi pays Rs 200",
                List.of(debit("RAVI", 20_000), credit("SETTLE", 20_000)));

        Map<String, Long> result = Postings.newBalances(accounts, raw, pay);

        assertEquals(Map.of("RAVI", -80_000L, "SETTLE", -20_000L), result);
        assertEquals(Map.of("RAVI", -100_000L, "SETTLE", 0L), raw); // input untouched
    }

    @Test
    void overdraftIsRefused() {
        Map<String, Long> raw = Map.of("RAVI", -10_000L, "SETTLE", 0L);
        JournalEntry pay = new JournalEntry("je-1", "txn-1", "Ravi pays Rs 200 with only Rs 100",
                List.of(debit("RAVI", 20_000), credit("SETTLE", 20_000)));

        assertThrows(InsufficientFundsException.class, () -> Postings.newBalances(accounts, raw, pay));
    }

    @Test
    void accountWithoutABalanceIsRefused() {
        Map<String, Long> raw = Map.of("RAVI", -100_000L); // SETTLE balance missing
        JournalEntry pay = new JournalEntry("je-1", "txn-1", "x",
                List.of(debit("RAVI", 20_000), credit("SETTLE", 20_000)));

        assertThrows(IllegalArgumentException.class, () -> Postings.newBalances(accounts, raw, pay));
    }
}

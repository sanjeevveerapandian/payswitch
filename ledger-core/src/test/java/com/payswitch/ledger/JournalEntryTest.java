package com.payswitch.ledger;

import static com.payswitch.ledger.EntryLine.credit;
import static com.payswitch.ledger.EntryLine.debit;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class JournalEntryTest {

    @Test
    void balancedEntryIsAccepted() {
        assertDoesNotThrow(() -> new JournalEntry("je-1", "txn-1", "ok",
                List.of(debit("A", 20_000), credit("B", 20_000))));
    }

    @Test
    void unbalancedEntryIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new JournalEntry("je-1", "txn-1", "bad",
                List.of(debit("A", 20_000), credit("B", 19_999))));
    }

    @Test
    void entryWithOneLineIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new JournalEntry("je-1", "txn-1", "bad",
                List.of(debit("A", 0))));
    }

    @Test
    void entryWithZeroAmountLineIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new JournalEntry("je-1", "txn-1", "bad",
                List.of(debit("A", 100), credit("B", 100), debit("C", 0))));
    }

    @Test
    void entryWhoseLinesOverflowLongIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new JournalEntry("je-1", "txn-1", "bad",
                List.of(debit("A", Long.MAX_VALUE), debit("B", 1), credit("C", 1))));
    }
}

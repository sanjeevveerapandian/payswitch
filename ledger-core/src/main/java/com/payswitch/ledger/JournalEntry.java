package com.payswitch.ledger;

import java.util.List;

/**
 * One business event, recorded as two or more lines that sum to exactly zero.
 * If an object of this type exists, it is valid: the constructor refuses bad entries.
 */
public record JournalEntry(String id, String txnId, String description, List<EntryLine> lines) {

    public JournalEntry {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("JournalEntry id must not be blank");
        }
        if (txnId == null || txnId.isBlank()) {
            throw new IllegalArgumentException("JournalEntry txnId must not be blank");
        }
        if (lines == null || lines.size() < 2) {
            throw new IllegalArgumentException("JournalEntry needs at least 2 lines");
        }
        lines = List.copyOf(lines); // immutable copy: nobody can change the lines later

        long sum = 0;
        for (EntryLine line : lines) {
            if (line.amount() == 0) {
                throw new IllegalArgumentException("JournalEntry line amount must not be 0");
            }
            try {
                sum = Math.addExact(sum, line.amount());
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("JournalEntry line amounts overflow", overflow);
            }
        }
        if (sum != 0) {
            throw new IllegalArgumentException("JournalEntry is unbalanced: lines sum to " + sum);
        }
    }
}

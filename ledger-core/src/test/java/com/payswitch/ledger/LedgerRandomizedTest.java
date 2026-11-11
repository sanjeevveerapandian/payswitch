package com.payswitch.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Throws 10,000 random entries (good and bad) at the ledger and checks that the
 * invariants never break and that every rejected entry changes nothing.
 */
class LedgerRandomizedTest {

    private static final long SEED = 20261111L; // fixed seed: a failure can be replayed exactly
    private static final int ROUNDS = 10_000;

    @Test
    void invariantsHoldAfterTenThousandRandomEntries() {
        Random random = new Random(SEED);
        Ledger ledger = new Ledger();
        ledger.openAccount(new Account("CASH", "Bank cash", AccountType.ASSET, false));
        ledger.openAccount(new Account("SETTLE", "Settlement with switch", AccountType.ASSET, true));
        ledger.openAccount(new Account("RAVI", "Ravi", AccountType.LIABILITY, false));
        ledger.openAccount(new Account("ANU", "Anu", AccountType.LIABILITY, false));
        ledger.openAccount(new Account("KIRAN", "Kiran", AccountType.LIABILITY, false));
        List<String> ids = ledger.accounts().stream().map(Account::id).toList();

        int posted = 0;
        int rejectedByConstructor = 0;
        int rejectedByLedger = 0;

        for (int round = 0; round < ROUNDS; round++) {
            JournalEntry entry;
            try {
                entry = randomEntry(random, ids, round);
            } catch (IllegalArgumentException invalid) {
                rejectedByConstructor++;
                continue;
            }

            Map<String, Long> before = snapshot(ledger, ids);
            int entriesBefore = ledger.allEntries().size();
            try {
                ledger.post(entry);
                posted++;
            } catch (InsufficientFundsException rejected) {
                rejectedByLedger++;
                assertEquals(before, snapshot(ledger, ids), "rejected entry changed a balance");
                assertEquals(entriesBefore, ledger.allEntries().size());
            }

            if (round % 500 == 0) {
                LedgerInvariants.check(ledger);
            }
        }
        LedgerInvariants.check(ledger);

        // Make sure the test really exercised all three paths.
        assertTrue(posted > 1_000, "posted=" + posted);
        assertTrue(rejectedByConstructor > 100, "rejectedByConstructor=" + rejectedByConstructor);
        assertTrue(rejectedByLedger > 100, "rejectedByLedger=" + rejectedByLedger);
    }

    /** About 80% balanced, 10% unbalanced, 10% with a zero line. */
    private static JournalEntry randomEntry(Random random, List<String> ids, int round) {
        int lineCount = 2 + random.nextInt(3);
        List<EntryLine> lines = new ArrayList<>();
        long sum = 0;
        for (int i = 0; i < lineCount - 1; i++) {
            long amount = 1 + random.nextInt(50_000);
            if (random.nextBoolean()) {
                amount = -amount;
            }
            lines.add(new EntryLine(pick(random, ids), amount));
            sum += amount;
        }
        long closing = -sum;
        int kind = random.nextInt(10);
        if (kind == 0) {
            closing += 1 + random.nextInt(100);     // unbalanced
        } else if (kind == 1) {
            closing = 0;                            // zero line
        }
        lines.add(new EntryLine(pick(random, ids), closing));
        return new JournalEntry("je-" + round, "txn-" + round, "random", lines);
    }

    private static String pick(Random random, List<String> ids) {
        return ids.get(random.nextInt(ids.size()));
    }

    private static Map<String, Long> snapshot(Ledger ledger, List<String> ids) {
        Map<String, Long> balances = new HashMap<>();
        for (String id : ids) {
            balances.put(id, ledger.rawBalance(id));
        }
        return balances;
    }
}

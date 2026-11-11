package com.payswitch.ledger;

import java.util.HashMap;
import java.util.Map;

/**
 * Rules that must be true of ANY ledger at ANY moment. Throws IllegalStateException if broken.
 *
 * <ol>
 *   <li>Raw balances of all accounts sum to 0 (money is never created or destroyed).</li>
 *   <li>Every no-negative account has a natural balance >= 0.</li>
 *   <li>Replaying all journal entries from zero gives exactly the stored balances.</li>
 * </ol>
 */
public final class LedgerInvariants {

    private LedgerInvariants() {
    }

    public static void check(Ledger ledger) {
        long total = 0;
        for (Account account : ledger.accounts()) {
            total = Math.addExact(total, ledger.rawBalance(account.id()));
            if (!account.allowNegative() && ledger.balance(account.id()) < 0) {
                throw new IllegalStateException("No-negative account is negative: " + account.id()
                        + " = " + ledger.balance(account.id()));
            }
        }
        if (total != 0) {
            throw new IllegalStateException("Sum of all raw balances is " + total + ", expected 0");
        }

        Map<String, Long> replayed = new HashMap<>();
        for (JournalEntry entry : ledger.allEntries()) {
            for (EntryLine line : entry.lines()) {
                replayed.merge(line.accountId(), line.amount(), Math::addExact);
            }
        }
        for (Account account : ledger.accounts()) {
            long expected = replayed.getOrDefault(account.id(), 0L);
            if (expected != ledger.rawBalance(account.id())) {
                throw new IllegalStateException("Balance of " + account.id() + " is "
                        + ledger.rawBalance(account.id()) + " but the journal says " + expected);
            }
        }
    }
}

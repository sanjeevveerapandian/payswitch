package com.payswitch.ledger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The posting rule as a pure function: given accounts, their current raw balances and an entry,
 * work out the new raw balances, or refuse. It changes nothing.
 *
 * <p>The in-memory {@link Ledger} and the Postgres-backed bank both call this, so the
 * "no-negative" and "same account on two lines" rules live in exactly one place.
 */
public final class Postings {

    private Postings() {
    }

    /**
     * @param accounts    every account the entry touches, by id
     * @param rawBalances current raw (debit-positive) balance of every account the entry touches
     * @return new raw balance for each touched account
     * @throws IllegalArgumentException   if a line names an account not in {@code accounts}
     * @throws InsufficientFundsException if a no-negative account would go below zero
     */
    public static Map<String, Long> newBalances(
            Map<String, Account> accounts, Map<String, Long> rawBalances, JournalEntry entry) {

        // Net change per account (the same account may appear on several lines).
        Map<String, Long> netChange = new LinkedHashMap<>();
        for (EntryLine line : entry.lines()) {
            if (!accounts.containsKey(line.accountId()) || !rawBalances.containsKey(line.accountId())) {
                throw new IllegalArgumentException("Unknown account: " + line.accountId());
            }
            netChange.merge(line.accountId(), line.amount(), Math::addExact);
        }

        Map<String, Long> newRaw = new LinkedHashMap<>();
        for (Map.Entry<String, Long> change : netChange.entrySet()) {
            Account account = accounts.get(change.getKey());
            long before = rawBalances.get(account.id());
            long after = Math.addExact(before, change.getValue());
            if (!account.allowNegative() && account.type().naturalBalance(after) < 0) {
                throw new InsufficientFundsException(account.id(),
                        account.type().naturalBalance(before), account.type().naturalBalance(after));
            }
            newRaw.put(account.id(), after);
        }
        return newRaw;
    }
}

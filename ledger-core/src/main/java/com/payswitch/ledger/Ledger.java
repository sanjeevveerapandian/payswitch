package com.payswitch.ledger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory double-entry ledger.
 *
 * <p>post() is all-or-nothing: it first checks every line, and only if all checks pass
 * does it change any balance. Methods are synchronized so two threads can never
 * interleave a check and an update.
 */
public final class Ledger {

    private final Map<String, Account> accounts = new LinkedHashMap<>();
    /** Raw balances, debit-positive. Their total is always 0. */
    private final Map<String, Long> rawBalances = new HashMap<>();
    private final List<JournalEntry> entries = new ArrayList<>();

    public synchronized void openAccount(Account account) {
        if (accounts.containsKey(account.id())) {
            throw new IllegalArgumentException("Account already exists: " + account.id());
        }
        accounts.put(account.id(), account);
        rawBalances.put(account.id(), 0L);
    }

    public synchronized void post(JournalEntry entry) {
        // Step 1: net change per account (the same account may appear on several lines).
        Map<String, Long> netChange = new LinkedHashMap<>();
        for (EntryLine line : entry.lines()) {
            if (!accounts.containsKey(line.accountId())) {
                throw new IllegalArgumentException("Unknown account: " + line.accountId());
            }
            netChange.merge(line.accountId(), line.amount(), Math::addExact);
        }

        // Step 2: work out every new balance and check it. Nothing is changed yet.
        Map<String, Long> newRaw = new HashMap<>();
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

        // Step 3: every check passed, so apply everything. No exception can happen below.
        rawBalances.putAll(newRaw);
        entries.add(entry);
    }

    /** Balance as a person reads it: Ravi with Rs 1000 in savings shows +100000. */
    public synchronized long balance(String accountId) {
        return account(accountId).type().naturalBalance(rawBalance(accountId));
    }

    /** Balance in the ledger's internal debit-positive form. */
    public synchronized long rawBalance(String accountId) {
        Long raw = rawBalances.get(accountId);
        if (raw == null) {
            throw new IllegalArgumentException("Unknown account: " + accountId);
        }
        return raw;
    }

    public synchronized Account account(String accountId) {
        Account account = accounts.get(accountId);
        if (account == null) {
            throw new IllegalArgumentException("Unknown account: " + accountId);
        }
        return account;
    }

    public synchronized List<Account> accounts() {
        return List.copyOf(accounts.values());
    }

    public synchronized List<JournalEntry> allEntries() {
        return List.copyOf(entries);
    }
}

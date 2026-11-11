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
        // Steps 1 and 2: work out and check every new balance. Nothing is changed yet.
        Map<String, Long> newRaw = Postings.newBalances(accounts, rawBalances, entry);

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

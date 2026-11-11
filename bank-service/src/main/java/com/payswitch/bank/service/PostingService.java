package com.payswitch.bank.service;

import com.payswitch.bank.db.AccountRepository;
import com.payswitch.bank.db.AccountRow;
import com.payswitch.bank.db.JournalRepository;
import com.payswitch.ledger.Account;
import com.payswitch.ledger.EntryLine;
import com.payswitch.ledger.InsufficientFundsException;
import com.payswitch.ledger.JournalEntry;
import com.payswitch.ledger.Postings;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes a journal entry to Postgres using ledger-core's rules.
 *
 * <p>MANDATORY: this must run inside a transaction someone else started. Row locks only last
 * until the transaction ends, so locking outside a transaction would protect nothing.
 */
@Service
public class PostingService {

    private final AccountRepository accounts;
    private final JournalRepository journal;

    public PostingService(AccountRepository accounts, JournalRepository journal) {
        this.accounts = accounts;
        this.journal = journal;
    }

    /**
     * @throws InsufficientFundsException if a no-negative account would go below zero.
     *         Nothing has been written when this is thrown.
     *
     * <p>noRollbackFor: by default, an exception leaving a @Transactional method marks the WHOLE
     * transaction "rollback only", so the caller could not save its DECLINED answer. Insufficient
     * funds is a normal business outcome and nothing has been written yet, so we allow commit.
     */
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = InsufficientFundsException.class)
    public void post(JournalEntry entry, String opType) {
        // 1. Lock every touched account, ALWAYS in sorted id order. If payment X locked
        //    (RAVI, SETTLEMENT) and payment Y locked (SETTLEMENT, RAVI), each could hold one
        //    lock and wait forever for the other: a deadlock. One global order prevents that.
        List<String> ids = entry.lines().stream().map(EntryLine::accountId).distinct().sorted().toList();
        Map<String, Account> lockedAccounts = new HashMap<>();
        Map<String, Long> rawBalances = new HashMap<>();
        for (String id : ids) {
            AccountRow row = accounts.lockById(id);
            lockedAccounts.put(id, row.toLedgerAccount());
            rawBalances.put(id, row.rawBalance());
        }

        // 2. ledger-core decides: new balances, or InsufficientFundsException. Pure Java, no DB.
        Map<String, Long> newRaw = Postings.newBalances(lockedAccounts, rawBalances, entry);

        // 3. Write. Still holding the locks, so nobody else changed these rows since step 1.
        journal.insert(entry, opType);
        newRaw.forEach(accounts::updateRawBalance);
    }
}

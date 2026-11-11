package com.payswitch.bank.service;

import static com.payswitch.ledger.EntryLine.credit;
import static com.payswitch.ledger.EntryLine.debit;

import com.payswitch.bank.db.AccountRepository;
import com.payswitch.bank.db.AccountRow;
import com.payswitch.ledger.AccountType;
import com.payswitch.ledger.JournalEntry;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opens a customer account with an opening cash deposit. Not exposed over HTTP yet; used by
 * tests now and by an admin API later. The deposit is a real journal entry, so the books
 * always balance: debit BANK_CASH, credit the customer.
 */
@Service
public class AccountOpeningService {

    public static final String BANK_CASH = "BANK_CASH";

    private final AccountRepository accounts;
    private final PostingService posting;

    public AccountOpeningService(AccountRepository accounts, PostingService posting) {
        this.accounts = accounts;
        this.posting = posting;
    }

    /** @return the new account id */
    @Transactional
    public String openCustomer(String vpa, String name, long openingPaise) {
        String id = "ACC-" + UUID.randomUUID();
        accounts.insert(new AccountRow(id, vpa, name, AccountType.LIABILITY, false, 0));
        if (openingPaise > 0) {
            posting.post(new JournalEntry(UUID.randomUUID().toString(), "OPEN-" + id, "Opening cash deposit",
                    List.of(debit(BANK_CASH, openingPaise), credit(id, openingPaise))), "OPENING");
        }
        return id;
    }
}

package com.payswitch.bank.db;

import com.payswitch.ledger.Account;
import com.payswitch.ledger.AccountType;

/** One row of the accounts table. */
public record AccountRow(String id, String vpa, String name, AccountType type, boolean allowNegative,
                         long rawBalance) {

    /** The ledger-core view of this account, so ledger-core's rules can be applied to it. */
    public Account toLedgerAccount() {
        return new Account(id, name, type, allowNegative);
    }

    /** Balance as the customer reads it (Ravi with Rs 5,000 sees 500000). */
    public long naturalBalance() {
        return type.naturalBalance(rawBalance);
    }
}

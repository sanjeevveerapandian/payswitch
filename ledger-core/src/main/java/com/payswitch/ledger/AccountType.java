package com.payswitch.ledger;

/**
 * What an account means from the BANK's point of view.
 *
 * <p>ASSET: something the bank owns (e.g. its cash). Debits increase it.
 * <p>LIABILITY: something the bank owes (e.g. Ravi's savings balance). Credits increase it.
 *
 * <p>Inside the ledger every amount is stored "debit-positive" (raw). To show a human the
 * balance they expect, we flip the sign for liabilities. That flip is {@link #naturalBalance}.
 */
public enum AccountType {
    ASSET,
    LIABILITY;

    /** Converts a raw (debit-positive) balance into the balance a person would read. */
    public long naturalBalance(long rawBalance) {
        return this == ASSET ? rawBalance : Math.negateExact(rawBalance);
    }
}

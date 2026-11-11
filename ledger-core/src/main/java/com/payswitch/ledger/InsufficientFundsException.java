package com.payswitch.ledger;

/** Thrown when posting an entry would push a no-negative account below zero. */
public class InsufficientFundsException extends RuntimeException {

    private final String accountId;

    public InsufficientFundsException(String accountId, long balanceNow, long balanceAfter) {
        super("Account " + accountId + " would go from " + balanceNow + " to " + balanceAfter + " paise");
        this.accountId = accountId;
    }

    public String accountId() {
        return accountId;
    }
}

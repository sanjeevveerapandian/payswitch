package com.payswitch.bank.service;

/** Thrown by read-only lookups (balance) when no account has this VPA. */
public class UnknownVpaException extends RuntimeException {

    public UnknownVpaException(String vpa) {
        super("No account with VPA " + vpa);
    }
}

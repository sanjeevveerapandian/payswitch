package com.payswitch.switchservice.service;

public class TransactionNotFoundException extends RuntimeException {

    public TransactionNotFoundException(String txnId) {
        super("No txn with id " + txnId);
    }
}

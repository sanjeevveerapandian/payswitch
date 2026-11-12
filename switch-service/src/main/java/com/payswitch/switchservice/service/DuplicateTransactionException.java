package com.payswitch.switchservice.service;

/** create() was called with a txnId that already exists. */
public class DuplicateTransactionException extends RuntimeException {

    public DuplicateTransactionException(String txnId) {
        super("Txn " + txnId + " already exists");
    }
}

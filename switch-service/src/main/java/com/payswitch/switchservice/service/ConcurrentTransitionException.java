package com.payswitch.switchservice.service;

/**
 * The optimistic lock fired: someone else changed this transaction after we read it.
 * Nothing was written. The caller should re-read the transaction and decide again.
 */
public class ConcurrentTransitionException extends RuntimeException {

    public ConcurrentTransitionException(String txnId, long expectedVersion) {
        super("Txn " + txnId + " was changed by someone else (expected version " + expectedVersion + ")");
    }
}

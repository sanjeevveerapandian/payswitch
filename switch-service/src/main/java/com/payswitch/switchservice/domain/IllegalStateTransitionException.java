package com.payswitch.switchservice.domain;

/** Someone tried a move that is not in the transition table, e.g. SUCCESS -> FAILED. */
public class IllegalStateTransitionException extends RuntimeException {

    private final TxnState from;
    private final TxnState to;

    public IllegalStateTransitionException(String txnId, TxnState from, TxnState to) {
        super("Txn " + txnId + ": " + from + " -> " + to + " is not allowed");
        this.from = from;
        this.to = to;
    }

    public TxnState from() {
        return from;
    }

    public TxnState to() {
        return to;
    }
}

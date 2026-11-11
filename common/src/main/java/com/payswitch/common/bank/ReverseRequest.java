package com.payswitch.common.bank;

/** Body of POST /reverse: undo the DEBIT with this txnId. */
public record ReverseRequest(String txnId) {

    public ReverseRequest {
        Validation.requireText(txnId, "txnId");
    }
}

package com.payswitch.common.bank;

import com.payswitch.common.Validation;

/** Switch -> payer's bank: undo the DEBIT with this txnId. Body of POST /reverse. */
public record ReverseRequest(String txnId) {

    public ReverseRequest {
        Validation.requireText(txnId, "txnId");
    }
}

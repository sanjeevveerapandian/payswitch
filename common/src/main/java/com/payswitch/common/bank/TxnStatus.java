package com.payswitch.common.bank;

/** Answer to "what happened to (txnId, op)?" */
public enum TxnStatus {
    SUCCESS,
    FAILED,
    NOT_FOUND
}

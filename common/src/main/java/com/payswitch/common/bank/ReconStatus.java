package com.payswitch.common.bank;

/** How one switch operation ended at a bank, as reconciliation sees it. */
public enum ReconStatus {
    /** Done: the money moved. */
    SUCCESS,
    /** Refused for a business reason (e.g. insufficient funds). No money moved. */
    DECLINED,
    /** Blocked by a tombstone (a fencing status check, or a reverse that came first). No money moved, ever. */
    FENCED,
    /** A REVERSE that found no debit to undo. No money moved. */
    NO_DEBIT
}

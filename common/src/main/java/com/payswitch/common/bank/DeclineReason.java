package com.payswitch.common.bank;

/** Why a bank said no. The switch uses this to tell the payer what went wrong. */
public enum DeclineReason {
    INSUFFICIENT_FUNDS,
    UNKNOWN_VPA,
    /** REVERSE for a debit this bank never received. */
    ORIGINAL_NOT_FOUND,
    /** REVERSE for a debit that was itself declined, so there is nothing to undo. */
    ORIGINAL_NOT_SUCCESSFUL,
    /** A DEBIT that arrived after its own REVERSE had already been processed. */
    ALREADY_REVERSED
}

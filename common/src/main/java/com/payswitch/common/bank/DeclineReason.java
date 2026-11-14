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
    ALREADY_REVERSED,
    /** Chaos testing only: the bank said no without even looking at the request. Nothing happened. */
    SIMULATED_DECLINE,
    /**
     * A status check found no record of this operation and fenced it: the bank promised the switch
     * it will never do it. Any later request for it gets this answer.
     */
    FENCED
}

package com.payswitch.switchservice.domain;

/**
 * Where one payment is in its life. Example: Ravi (Bank A) pays Rs 200 to Anu's shop (Bank B).
 * The comment on each state says where the Rs 200 is while the payment sits in that state.
 */
public enum TxnState {

    /** Recorded by the switch, nothing sent yet. Money: with Ravi. */
    INITIATED,

    /** Debit request sent to Bank A, no answer yet. Money: with Ravi OR already taken. Unknown. */
    DEBIT_SENT,

    /** Bank A confirmed the debit. Money: left Ravi, held in Bank A's settlement account. */
    DEBITED,

    /** Credit request sent to Bank B, no answer yet. Money: in settlement, OR already with Anu. Unknown. */
    CREDIT_SENT,

    /** Bank B confirmed the credit. Money: with Anu. Terminal. */
    SUCCESS,

    /** The payment did not happen and Ravi was never (or is no longer) out of pocket. Money: with Ravi. Terminal. */
    FAILED,

    /** Reversal request sent to Bank A, no answer yet. Money: in settlement, OR already back with Ravi. Unknown. */
    REVERSAL_SENT,

    /** Bank A confirmed the reversal. Money: back with Ravi. Terminal. */
    REVERSED,

    /**
     * Bank B did not answer the credit in time. We treat it as credited and tell Ravi "paid",
     * then confirm with Bank B later. Money: believed to be with Anu, not yet proven.
     */
    DEEMED_APPROVED
}

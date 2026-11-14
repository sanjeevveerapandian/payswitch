package com.payswitch.bank.chaos;

/** What chaos does to one /debit, /credit or /reverse call. */
public enum ChaosDecision {
    /** Handle the call normally. */
    PROCEED,
    /** Answer DECLINED. Nothing is written. */
    DECLINE,
    /** Answer HTTP 500. Nothing is written. */
    ERROR,
    /** Do the operation and commit it, then stay silent until the caller has given up. */
    DROP_RESPONSE
}

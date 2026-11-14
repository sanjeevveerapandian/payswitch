package com.payswitch.switchservice.bank;

/**
 * Everything a bank call can end in, from the switch's point of view. Exactly three values.
 */
public enum BankCallOutcome {

    /** The bank answered, clearly, "done". The money moved. */
    SUCCESS,

    /** The bank answered, clearly, "no". The money did not move. */
    DECLINED,

    /**
     * We have no clear answer: timeout, connection refused or reset, HTTP 5xx, a body we could
     * not read. The money MAY have moved or may not. Never treat this as SUCCESS or DECLINED.
     */
    UNKNOWN
}

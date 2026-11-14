package com.payswitch.common.bank;

/** Outcome of a bank operation. DECLINED is a normal business answer, not an error. */
public enum BankResult {
    SUCCESS,
    DECLINED,
    /**
     * REVERSE only: there was no debit to undo, so the payer's money never left. The bank has also
     * blocked any debit for this txnId that arrives later, so it never will leave.
     */
    NO_DEBIT
}

package com.payswitch.common.bank;

/** Outcome of a bank operation. DECLINED is a normal business answer, not an error. */
public enum BankResult {
    SUCCESS,
    DECLINED
}

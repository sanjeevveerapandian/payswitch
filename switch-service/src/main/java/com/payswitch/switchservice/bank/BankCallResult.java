package com.payswitch.switchservice.bank;

import com.payswitch.common.bank.DeclineReason;
import java.util.Objects;

/**
 * The outcome of one bank call, plus a short human-readable detail for the audit trail.
 *
 * @param declineReason set only when outcome is DECLINED (may still be null if the bank gave none)
 * @param detail        DECLINED: the reason's name, e.g. "INSUFFICIENT_FUNDS".
 *                      UNKNOWN: what went wrong, e.g. "timeout", "HTTP 500". SUCCESS: "OK".
 */
public record BankCallResult(BankCallOutcome outcome, DeclineReason declineReason, String detail) {

    private static final int MAX_DETAIL = 60;   // fits in txn_events.reason with a prefix

    public BankCallResult {
        Objects.requireNonNull(outcome, "outcome");
        detail = detail == null || detail.isBlank() ? "no detail" : detail;
        if (detail.length() > MAX_DETAIL) {
            detail = detail.substring(0, MAX_DETAIL);
        }
    }

    public static BankCallResult success() {
        return new BankCallResult(BankCallOutcome.SUCCESS, null, "OK");
    }

    /** A REVERSE answered NO_DEBIT: nothing to undo, and a later debit is blocked. */
    public static BankCallResult successNoDebit() {
        return new BankCallResult(BankCallOutcome.SUCCESS, null, "NO_DEBIT");
    }

    public boolean isNoDebit() {
        return outcome == BankCallOutcome.SUCCESS && "NO_DEBIT".equals(detail);
    }

    public static BankCallResult declined(DeclineReason reason) {
        return new BankCallResult(BankCallOutcome.DECLINED, reason, reason == null ? "UNSPECIFIED" : reason.name());
    }

    public static BankCallResult unknown(String why) {
        return new BankCallResult(BankCallOutcome.UNKNOWN, null, why);
    }
}

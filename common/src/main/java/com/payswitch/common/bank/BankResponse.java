package com.payswitch.common.bank;

/**
 * What the bank answers to DEBIT, CREDIT and REVERSE. This exact object is saved, and a retry
 * of the same (txnId, op) gets it back unchanged.
 *
 * @param reason null when result is SUCCESS
 */
public record BankResponse(String txnId, OpType op, BankResult result, DeclineReason reason, String message) {

    public static BankResponse success(String txnId, OpType op) {
        return new BankResponse(txnId, op, BankResult.SUCCESS, null, "OK");
    }

    public static BankResponse declined(String txnId, OpType op, DeclineReason reason, String message) {
        return new BankResponse(txnId, op, BankResult.DECLINED, reason, message);
    }
}

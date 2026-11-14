package com.payswitch.common.bank;

/**
 * Bank -> switch: answer to POST /status-check.
 *
 * @param saved the exact answer the bank saved for this operation when result is FOUND
 *              (also the tombstone when FENCED); null when NOT_FOUND
 */
public record StatusCheckResponse(String txnId, OpType op, CheckResult result, BankResponse saved) {
}

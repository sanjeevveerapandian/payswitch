package com.payswitch.common.pay;

/**
 * Switch -> PSP -> customer: what became of the payment.
 *
 * @param state  SUCCESS, FAILED, REVERSED, or PENDING when the outcome is not known yet
 * @param reason the latest step in the switch's history, e.g. "DEBIT_DECLINED:INSUFFICIENT_FUNDS"
 *               or "DEBIT_UNKNOWN: timeout"
 */
public record RespPay(String txnId, PayState state, String reason) {
}

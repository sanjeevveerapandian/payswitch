package com.payswitch.common.pay;

/**
 * Switch -> PSP: what became of the payment.
 *
 * @param state  the switch's state for this payment, e.g. SUCCESS, FAILED, REVERSED
 * @param reason why it is in that state, e.g. "DEBIT_DECLINED:INSUFFICIENT_FUNDS"
 */
public record RespPay(String txnId, String state, String reason) {
}

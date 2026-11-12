package com.payswitch.common.pay;

import java.util.List;

/**
 * Body of the switch's GET /txns/{txnId}: the payment as it is now, plus its full history.
 *
 * @param amount in paise
 * @param reason reason of the latest event
 * @param events oldest first
 */
public record TxnDetails(String txnId, String payerVpa, String payeeVpa, String payerBank, String payeeBank,
                         long amount, String state, String reason, List<TxnEventView> events) {
}

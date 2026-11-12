package com.payswitch.common.pay;

import com.payswitch.common.Validation;

/**
 * PSP -> Switch: "please make this payment". Body of the switch's POST /pay.
 *
 * @param txnId  made up by the PSP, unique per payment attempt; every later message about this
 *               payment (debit, credit, reverse, status) carries the same id
 * @param amount in paise
 */
public record ReqPay(String txnId, String payerVpa, String payeeVpa, long amount) {

    public ReqPay {
        Validation.requireText(txnId, "txnId");
        Validation.requireText(payerVpa, "payerVpa");
        Validation.requireText(payeeVpa, "payeeVpa");
        Validation.requireAmount(amount);
    }
}

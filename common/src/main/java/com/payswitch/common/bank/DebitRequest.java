package com.payswitch.common.bank;

import com.payswitch.common.Validation;

/**
 * Switch -> payer's bank: take this amount from the customer. Body of POST /debit.
 *
 * @param amount in paise (Rs 200 = 20000). Services talk in paise; only the PSP's public
 *               API converts from rupees.
 */
public record DebitRequest(String txnId, String vpa, long amount) {

    public DebitRequest {
        Validation.requireText(txnId, "txnId");
        Validation.requireText(vpa, "vpa");
        Validation.requireAmount(amount);
    }
}

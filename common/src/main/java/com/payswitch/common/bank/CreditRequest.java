package com.payswitch.common.bank;

import com.payswitch.common.Validation;

/**
 * Switch -> payee's bank: give this amount to the customer. Body of POST /credit.
 *
 * @param amount in paise (Rs 200 = 20000)
 */
public record CreditRequest(String txnId, String vpa, long amount) {

    public CreditRequest {
        Validation.requireText(txnId, "txnId");
        Validation.requireText(vpa, "vpa");
        Validation.requireAmount(amount);
    }
}

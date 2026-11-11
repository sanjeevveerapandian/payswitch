package com.payswitch.common.bank;

/**
 * Body of POST /debit and POST /credit.
 *
 * @param amount in paise (Rs 200 = 20000). Services talk in paise; only the PSP's public
 *               API converts from rupees.
 */
public record BankTxnRequest(String txnId, String vpa, long amount) {

    /** UPI's per-transaction cap: Rs 1,00,000. */
    public static final long MAX_AMOUNT_PAISE = 10_000_000L;

    public BankTxnRequest {
        Validation.requireText(txnId, "txnId");
        Validation.requireText(vpa, "vpa");
        if (amount <= 0 || amount > MAX_AMOUNT_PAISE) {
            throw new IllegalArgumentException(
                    "amount must be between 1 and " + MAX_AMOUNT_PAISE + " paise, got " + amount);
        }
    }
}

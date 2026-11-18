package com.payswitch.switchservice.settlement;

/**
 * One payment inside a settlement cycle. Ravi (Bank A) paid Anu's shop (Bank B) Rs 200:
 * {@code new SettlementItem("TXN-1", "BANKA", "BANKB", 20000)}.
 *
 * @param amount paise
 */
public record SettlementItem(String txnId, String payerBank, String payeeBank, long amount) {

    public SettlementItem {
        if (amount <= 0) {
            throw new IllegalArgumentException("settlement item amount must be positive");
        }
    }
}

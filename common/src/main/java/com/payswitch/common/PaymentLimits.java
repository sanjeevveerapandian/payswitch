package com.payswitch.common;

public final class PaymentLimits {

    /** UPI's per-transaction cap: Rs 1,00,000 = 1,00,00,000 paise. */
    public static final long MAX_AMOUNT_PAISE = 10_000_000L;

    private PaymentLimits() {
    }
}

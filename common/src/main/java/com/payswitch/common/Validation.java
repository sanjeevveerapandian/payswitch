package com.payswitch.common;

/** Small checks shared by the request records. A record that exists has passed them. */
public final class Validation {

    private Validation() {
    }

    public static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > 64) {
            throw new IllegalArgumentException(field + " must be at most 64 characters");
        }
    }

    /** Amounts are paise: more than zero, and at most the per-payment cap. */
    public static void requireAmount(long paise) {
        if (paise <= 0 || paise > PaymentLimits.MAX_AMOUNT_PAISE) {
            throw new IllegalArgumentException("amount must be between 1 and "
                    + PaymentLimits.MAX_AMOUNT_PAISE + " paise, got " + paise);
        }
    }
}

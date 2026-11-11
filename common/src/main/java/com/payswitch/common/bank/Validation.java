package com.payswitch.common.bank;

final class Validation {

    private Validation() {
    }

    static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (value.length() > 64) {
            throw new IllegalArgumentException(field + " must be at most 64 characters");
        }
    }
}

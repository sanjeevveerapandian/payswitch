package com.payswitch.ledger;

import java.util.Objects;

/**
 * One account in the ledger, e.g. "Ravi savings" (LIABILITY, cannot go negative).
 *
 * @param allowNegative false for customer accounts: their natural balance must stay >= 0.
 */
public record Account(String id, String name, AccountType type, boolean allowNegative) {

    public Account {
        requireText(id, "id");
        requireText(name, "name");
        Objects.requireNonNull(type, "type");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Account " + field + " must not be blank");
        }
    }
}

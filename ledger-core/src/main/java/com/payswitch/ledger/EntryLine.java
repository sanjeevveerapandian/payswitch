package com.payswitch.ledger;

/**
 * One line of a journal entry.
 *
 * @param amount in paise. Positive = debit, negative = credit. Rs 200 = 20000.
 */
public record EntryLine(String accountId, long amount) {

    public EntryLine {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("EntryLine accountId must not be blank");
        }
    }

    public static EntryLine debit(String accountId, long paise) {
        return new EntryLine(accountId, paise);
    }

    public static EntryLine credit(String accountId, long paise) {
        return new EntryLine(accountId, Math.negateExact(paise));
    }
}

package com.payswitch.common.bank;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.payswitch.common.PaymentLimits;
import org.junit.jupiter.api.Test;

class BankRequestsTest {

    @Test
    void validRequestsAreAccepted() {
        assertDoesNotThrow(() -> new DebitRequest("txn-1", "ravi@banka", 20_000));
        assertDoesNotThrow(() -> new DebitRequest("txn-1", "ravi@banka", PaymentLimits.MAX_AMOUNT_PAISE));
        assertDoesNotThrow(() -> new CreditRequest("txn-1", "anu@bankb", 20_000));
        assertDoesNotThrow(() -> new CreditRequest("txn-1", "anu@bankb", PaymentLimits.MAX_AMOUNT_PAISE));
    }

    @Test
    void zeroNegativeAndOverLimitAmountsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DebitRequest("txn-1", "ravi@banka", 0));
        assertThrows(IllegalArgumentException.class, () -> new DebitRequest("txn-1", "ravi@banka", -1));
        assertThrows(IllegalArgumentException.class,
                () -> new DebitRequest("txn-1", "ravi@banka", PaymentLimits.MAX_AMOUNT_PAISE + 1));
        assertThrows(IllegalArgumentException.class, () -> new CreditRequest("txn-1", "anu@bankb", 0));
        assertThrows(IllegalArgumentException.class, () -> new CreditRequest("txn-1", "anu@bankb", -1));
        assertThrows(IllegalArgumentException.class,
                () -> new CreditRequest("txn-1", "anu@bankb", PaymentLimits.MAX_AMOUNT_PAISE + 1));
    }

    @Test
    void blankOrTooLongFieldsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DebitRequest(" ", "ravi@banka", 100));
        assertThrows(IllegalArgumentException.class, () -> new DebitRequest("txn-1", null, 100));
        assertThrows(IllegalArgumentException.class, () -> new DebitRequest("x".repeat(65), "ravi@banka", 100));
        assertThrows(IllegalArgumentException.class, () -> new CreditRequest(" ", "anu@bankb", 100));
        assertThrows(IllegalArgumentException.class, () -> new CreditRequest("txn-1", null, 100));
        assertThrows(IllegalArgumentException.class, () -> new ReverseRequest(""));
    }
}

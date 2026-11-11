package com.payswitch.common.bank;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BankTxnRequestTest {

    @Test
    void validRequestIsAccepted() {
        assertDoesNotThrow(() -> new BankTxnRequest("txn-1", "ravi@banka", 20_000));
        assertDoesNotThrow(() -> new BankTxnRequest("txn-1", "ravi@banka", BankTxnRequest.MAX_AMOUNT_PAISE));
    }

    @Test
    void zeroNegativeAndOverLimitAmountsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BankTxnRequest("txn-1", "ravi@banka", 0));
        assertThrows(IllegalArgumentException.class, () -> new BankTxnRequest("txn-1", "ravi@banka", -1));
        assertThrows(IllegalArgumentException.class,
                () -> new BankTxnRequest("txn-1", "ravi@banka", BankTxnRequest.MAX_AMOUNT_PAISE + 1));
    }

    @Test
    void blankOrTooLongFieldsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BankTxnRequest(" ", "ravi@banka", 100));
        assertThrows(IllegalArgumentException.class, () -> new BankTxnRequest("txn-1", null, 100));
        assertThrows(IllegalArgumentException.class, () -> new BankTxnRequest("x".repeat(65), "ravi@banka", 100));
        assertThrows(IllegalArgumentException.class, () -> new ReverseRequest(""));
    }
}

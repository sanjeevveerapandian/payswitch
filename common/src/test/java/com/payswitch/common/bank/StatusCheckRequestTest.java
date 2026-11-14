package com.payswitch.common.bank;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class StatusCheckRequestTest {

    @Test
    void debitAndCreditCanBeCheckedWithOrWithoutFence() {
        assertDoesNotThrow(() -> new StatusCheckRequest("T1", OpType.DEBIT, true));
        assertDoesNotThrow(() -> new StatusCheckRequest("T1", OpType.CREDIT, true));
        assertDoesNotThrow(() -> new StatusCheckRequest("T1", OpType.DEBIT, false));
        assertDoesNotThrow(() -> new StatusCheckRequest("T1", OpType.REVERSE, false));
    }

    @Test
    void aReverseCanNeverBeFenced() {
        assertThrows(IllegalArgumentException.class, () -> new StatusCheckRequest("T1", OpType.REVERSE, true));
    }

    @Test
    void missingFieldsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new StatusCheckRequest(" ", OpType.DEBIT, true));
        assertThrows(IllegalArgumentException.class, () -> new StatusCheckRequest("T1", null, true));
    }
}

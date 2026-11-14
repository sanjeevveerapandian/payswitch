package com.payswitch.common.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class PayStateTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "SUCCESS,         SUCCESS",
            "FAILED,          FAILED",
            "REVERSED,        REVERSED",
            "INITIATED,       PENDING",
            "DEBIT_SENT,      PENDING",
            "DEBITED,         PENDING",
            "CREDIT_SENT,     PENDING",
            "REVERSAL_SENT,   PENDING",
            "DEEMED_APPROVED, PENDING",
    })
    void everySwitchStateMapsToWhatTheCustomerSees(String switchState, PayState expected) {
        assertEquals(expected, PayState.fromSwitchState(switchState));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "success", "DONE"})
    void unknownSwitchStateIsRejected(String switchState) {
        assertThrows(IllegalArgumentException.class, () -> PayState.fromSwitchState(switchState));
    }
}

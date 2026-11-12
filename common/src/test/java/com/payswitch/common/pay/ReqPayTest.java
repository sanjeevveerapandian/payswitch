package com.payswitch.common.pay;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.payswitch.common.PaymentLimits;
import org.junit.jupiter.api.Test;

class ReqPayTest {

    @Test
    void validReqPayIsAccepted() {
        assertDoesNotThrow(() -> new ReqPay("txn-1", "ravi@banka", "anu@bankb", 20_000));
    }

    @Test
    void missingFieldsAndBadAmountsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ReqPay(null, "ravi@banka", "anu@bankb", 20_000));
        assertThrows(IllegalArgumentException.class, () -> new ReqPay("txn-1", " ", "anu@bankb", 20_000));
        assertThrows(IllegalArgumentException.class, () -> new ReqPay("txn-1", "ravi@banka", null, 20_000));
        assertThrows(IllegalArgumentException.class, () -> new ReqPay("txn-1", "ravi@banka", "anu@bankb", 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ReqPay("txn-1", "ravi@banka", "anu@bankb", PaymentLimits.MAX_AMOUNT_PAISE + 1));
    }
}

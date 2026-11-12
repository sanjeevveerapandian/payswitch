package com.payswitch.psp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class RupeeAmountTest {

    @ParameterizedTest(name = "Rs {0} = {1} paise")
    @CsvSource({
            "200,        20000",
            "200.5,      20050",
            "200.50,     20050",
            "200.05,     20005",
            "0.01,       1",
            "0.1,        10",
            "1,          100",
            "99999.99,   9999999",
            "100000,     10000000",
            "100000.00,  10000000",
            "0200,       20000",
            "' 200 ',    20000",
            // Amounts a double cannot hold exactly. BigDecimal must get them right.
            "200.10,     20010",
            "1.15,       115",
            "8.2,        820",
            "1005.99,    100599",
    })
    void convertsRupeesToPaiseExactly(String rupees, long paise) {
        assertEquals(paise, RupeeAmount.toPaise(rupees));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "", " ", "abc", "0", "0.0", "0.00",          // nothing or zero
            "-5", "-0.01", "+5",                         // signs
            "1.234", "200.505", "0.001",                 // more than 2 decimals
            "100000.01", "100001", "999999999",          // over Rs 1,00,000
            "1e3", "1E3", "0x10", "NaN", "Infinity",     // not plain decimals
            "1,000", "200.", ".50", "2 00", "200.5.0", "Rs 200", "200rs"})
    void rejectsInvalidAmounts(String rupees) {
        assertThrows(IllegalArgumentException.class, () -> RupeeAmount.toPaise(rupees));
    }
}

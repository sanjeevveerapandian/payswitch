package com.payswitch.load;

import java.math.BigDecimal;

/** Paise to the rupee text the PSP expects, e.g. 20050 -> "200.50". Exact: BigDecimal, never double. */
public final class Rupees {

    private Rupees() {
    }

    public static String text(long paise) {
        return BigDecimal.valueOf(paise, 2).toPlainString();
    }

    /** For reports: 123456789 -> "Rs 1234567.89". */
    public static String rs(long paise) {
        return "Rs " + text(paise);
    }
}

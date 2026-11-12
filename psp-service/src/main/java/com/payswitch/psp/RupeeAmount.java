package com.payswitch.psp;

import com.payswitch.common.PaymentLimits;
import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * The API edge: the ONE place where rupees typed by a person ("200.50") become paise (20050).
 * Past this point the whole system uses long paise only.
 */
public final class RupeeAmount {

    // Plain digits with an optional 1 or 2 decimals. No sign, no commas, no "1e3".
    private static final Pattern FORMAT = Pattern.compile("^\\d{1,9}(\\.\\d{1,2})?$");

    private static final BigDecimal MAX_RUPEES =
            BigDecimal.valueOf(PaymentLimits.MAX_AMOUNT_PAISE).movePointLeft(2);

    private RupeeAmount() {
    }

    /**
     * @param rupees e.g. "200", "200.5", "200.50"
     * @return paise, e.g. 20000, 20050, 20050
     * @throws IllegalArgumentException if it is not a valid amount between Rs 0.01 and Rs 1,00,000
     */
    public static long toPaise(String rupees) {
        if (rupees == null || !FORMAT.matcher(rupees.trim()).matches()) {
            throw new IllegalArgumentException(
                    "amount must be rupees with at most 2 decimals, like \"200\" or \"200.50\"");
        }
        // BigDecimal keeps "200.10" as exactly 200.10. A double would hold 200.099999...
        BigDecimal value = new BigDecimal(rupees.trim());
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("amount must be more than 0");
        }
        if (value.compareTo(MAX_RUPEES) > 0) {
            throw new IllegalArgumentException("amount must be at most Rs " + MAX_RUPEES.toPlainString());
        }
        // x100 by moving the decimal point. longValueExact throws if anything would be lost.
        return value.movePointRight(2).longValueExact();
    }
}

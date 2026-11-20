package com.payswitch.load;

import java.util.List;

/**
 * Who exists for the load test. Mirrors db/bank/loadtest/BANK?/V200__load_customers.sql exactly:
 * cust001@banka .. cust300@bankc, every 20th one "poor" (Rs 500), everyone else Rs 2,00,000.
 */
public final class LoadCustomers {

    /** Bank handles, in bank-code order: banka = BANKA, and so on. */
    public static final List<String> HANDLES = List.of("banka", "bankb", "bankc");
    public static final int PER_BANK = 300;

    public static final long POOR_OPENING_PAISE = 50_000L;          // Rs 500
    public static final long RICH_OPENING_PAISE = 20_000_000L;      // Rs 2,00,000

    private LoadCustomers() {
    }

    /** @param number 1..PER_BANK */
    public static String vpa(int number, String handle) {
        if (number < 1 || number > PER_BANK) {
            throw new IllegalArgumentException("customer number must be 1.." + PER_BANK + ", got " + number);
        }
        return String.format("cust%03d@%s", number, handle);
    }

    public static boolean isPoor(int number) {
        return number % 20 == 0;
    }

    public static long openingPaise(int number) {
        return isPoor(number) ? POOR_OPENING_PAISE : RICH_OPENING_PAISE;
    }

    /** All the load customers' opening money at ONE bank. */
    public static long openingTotalPerBank() {
        long total = 0;
        for (int i = 1; i <= PER_BANK; i++) {
            total += openingPaise(i);
        }
        return total;
    }
}

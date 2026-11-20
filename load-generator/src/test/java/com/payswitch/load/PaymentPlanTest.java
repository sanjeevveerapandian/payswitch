package com.payswitch.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.payswitch.load.PaymentPlan.Planned;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PaymentPlanTest {

    private static final long MAX_PAISE = 10_000_000L;                // Rs 1,00,000, UPI's cap

    @Test
    void sameSeedSamePaymentsDifferentSeedDifferentPayments() {
        assertEquals(PaymentPlan.generate(42, 1_000), PaymentPlan.generate(42, 1_000));
        assertNotEquals(PaymentPlan.generate(42, 1_000), PaymentPlan.generate(43, 1_000));
    }

    @Test
    void nobodyPaysThemselvesAndEveryoneExists() {
        Set<String> customers = new HashSet<>();
        for (String handle : LoadCustomers.HANDLES) {
            for (int i = 1; i <= LoadCustomers.PER_BANK; i++) {
                customers.add(LoadCustomers.vpa(i, handle));
            }
        }
        for (Planned p : PaymentPlan.generate(7, 20_000)) {
            assertNotEquals(p.payerVpa(), p.payeeVpa());
            assertTrue(customers.contains(p.payerVpa()) && customers.contains(p.payeeVpa()), p.toString());
            assertTrue(p.paise() >= 100 && p.paise() <= MAX_PAISE, p.toString());
        }
        assertThrows(IllegalArgumentException.class, () -> new Planned(0, "a@banka", "a@banka", 100, false));
    }

    /** Mostly small, a few large, all three banks used, some deliberate overdraft attempts. */
    @Test
    void amountsAreSkewedLikeRealTraffic() {
        List<Planned> plan = PaymentPlan.generate(1, 100_000);
        long small = plan.stream().filter(p -> !p.overdraftAttempt() && p.paise() <= 50_000).count();      // <= Rs 500
        long large = plan.stream().filter(p -> !p.overdraftAttempt() && p.paise() > 2_000_000).count();     // > Rs 20,000
        long overdraft = plan.stream().filter(Planned::overdraftAttempt).count();
        assertTrue(small > 75_000 && small < 82_000, "small " + small);       // ~80% of the 99%
        assertTrue(large > 700 && large < 1_300, "large " + large);           // ~1%
        assertTrue(overdraft > 800 && overdraft < 1_200, "overdraft attempts " + overdraft);
        for (Planned p : plan) {
            if (p.overdraftAttempt()) {
                int number = Integer.parseInt(p.payerVpa().substring(4, 7));
                assertTrue(LoadCustomers.isPoor(number), p.payerVpa());
                assertTrue(p.paise() > LoadCustomers.POOR_OPENING_PAISE, "must exceed what a poor customer starts with");
            }
        }
        Set<String> payerBanks = new HashSet<>();
        plan.forEach(p -> payerBanks.add(p.payerVpa().substring(p.payerVpa().indexOf('@') + 1)));
        assertEquals(Set.copyOf(LoadCustomers.HANDLES), payerBanks);
    }

    @Test
    void customersMatchTheSeedSql() {
        assertEquals("cust001@banka", LoadCustomers.vpa(1, "banka"));
        assertEquals("cust300@bankc", LoadCustomers.vpa(300, "bankc"));
        assertThrows(IllegalArgumentException.class, () -> LoadCustomers.vpa(301, "banka"));
        // The seed SQL adds 15 x 50000 + 285 x 20000000 to BANK_CASH: the same total.
        assertEquals(15 * 50_000L + 285 * 20_000_000L, LoadCustomers.openingTotalPerBank());
    }

    @Test
    void rupeeTextIsExact() {
        assertEquals("200.50", Rupees.text(20050));
        assertEquals("0.01", Rupees.text(1));
        assertEquals("100000.00", Rupees.text(MAX_PAISE));
    }
}

package com.payswitch.e2e;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Where all the money is, in paise.
 *
 * @param customersA  total of every customer balance at Bank A
 * @param settlementA what Bank A owes the switch (negative = the switch owes Bank A)
 */
record Money(long customersA, long settlementA, long customersB, long settlementB) {

    static Money snapshot(PaymentSystem system) {
        return new Money(customers(system.bankADb()), settlement(system.bankADb()),
                customers(system.bankBDb()), settlement(system.bankBDb()));
    }

    /**
     * Customers' money plus money in transit (the two settlement accounts). This total can never
     * change, whatever happens: a payment only moves money between these four places.
     */
    long total() {
        return customersA + settlementA + customersB + settlementB;
    }

    // Customer and settlement accounts are liabilities: natural balance = -raw_balance.
    private static long customers(JdbcClient db) {
        return db.sql("SELECT COALESCE(SUM(-raw_balance), 0) FROM accounts WHERE vpa IS NOT NULL")
                .query(Long.class).single();
    }

    private static long settlement(JdbcClient db) {
        return db.sql("SELECT -raw_balance FROM accounts WHERE id = 'SWITCH_SETTLEMENT'")
                .query(Long.class).single();
    }
}

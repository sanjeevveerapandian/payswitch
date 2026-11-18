package com.payswitch.e2e;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Where all the money is, in paise.
 *
 * @param customersA  total of every customer balance at Bank A
 * @param settlementA what Bank A owes the switch (negative = the switch owes Bank A)
 */
record Money(long customersA, long settlementA, long customersB, long settlementB,
             long customersC, long settlementC) {

    static Money snapshot(PaymentSystem system) {
        return new Money(customers(system.bankADb()), settlement(system.bankADb()),
                customers(system.bankBDb()), settlement(system.bankBDb()),
                customers(system.bankCDb()), settlement(system.bankCDb()));
    }

    /**
     * Customers' money plus money in transit (the settlement accounts). This total can never
     * change, whatever happens: a payment only moves money between these places. Settlement does not
     * change it either: it changes the banks' SWITCH_SETTLEMENT balances by amounts that sum to zero.
     */
    long total() {
        return customers() + settlementA + settlementB + settlementC;
    }

    /** Every customer's money, across all three banks. */
    long customers() {
        return customersA + customersB + customersC;
    }

    // Customer and settlement accounts are liabilities: natural balance = -raw_balance.
    static long customers(JdbcClient db) {
        return db.sql("SELECT COALESCE(SUM(-raw_balance), 0) FROM accounts WHERE vpa IS NOT NULL")
                .query(Long.class).single();
    }

    static long settlement(JdbcClient db) {
        return db.sql("SELECT -raw_balance FROM accounts WHERE id = 'SWITCH_SETTLEMENT'")
                .query(Long.class).single();
    }
}

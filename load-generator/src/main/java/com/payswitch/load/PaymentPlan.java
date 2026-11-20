package com.payswitch.load;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The list of payments to send, made from a seed: the same seed always gives the same payments,
 * so a run that found a bug can be repeated exactly.
 *
 * <p>Amounts are skewed like real UPI traffic: mostly small, a few large. Every amount is drawn as a
 * whole number of paise (a long). No double ever touches an amount.
 *
 * <pre>
 *   50%   Rs 1      - Rs 100        tea, auto rides
 *   30%   Rs 100    - Rs 500        groceries
 *   15%   Rs 500    - Rs 2,000
 *    4%   Rs 2,000  - Rs 20,000
 *    1%   Rs 20,000 - Rs 1,00,000   rent, a phone
 * </pre>
 * Plus about 1% "overdraft attempts": a poor customer (opened with Rs 500) tries to pay
 * Rs 50,000 - Rs 1,00,000. Most of these must be DECLINED for insufficient funds.
 */
public final class PaymentPlan {

    /** One payment to send. */
    public record Planned(int index, String payerVpa, String payeeVpa, long paise, boolean overdraftAttempt) {

        public Planned {
            if (payerVpa.equals(payeeVpa)) {
                throw new IllegalArgumentException("payer and payee must differ: " + payerVpa);
            }
            if (paise <= 0) {
                throw new IllegalArgumentException("amount must be positive");
            }
        }
    }

    private static final long RUPEE = 100;

    private PaymentPlan() {
    }

    public static List<Planned> generate(long seed, int count) {
        if (count < 0) {
            throw new IllegalArgumentException("count must be >= 0");
        }
        Random random = new Random(seed);
        List<Planned> plan = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            boolean overdraft = random.nextInt(100) == 0;                 // ~1%
            String payer = overdraft ? randomPoorCustomer(random) : randomCustomer(random);
            String payee;
            do {
                payee = randomCustomer(random);
            } while (payee.equals(payer));                                // never pay yourself
            long paise = overdraft ? between(random, 50_000 * RUPEE, 100_000 * RUPEE) : skewedAmount(random);
            plan.add(new Planned(i, payer, payee, paise, overdraft));
        }
        return plan;
    }

    static long skewedAmount(Random random) {
        int bucket = random.nextInt(100);
        if (bucket < 50) {
            return between(random, RUPEE, 100 * RUPEE);
        }
        if (bucket < 80) {
            return between(random, 100 * RUPEE, 500 * RUPEE);
        }
        if (bucket < 95) {
            return between(random, 500 * RUPEE, 2_000 * RUPEE);
        }
        if (bucket < 99) {
            return between(random, 2_000 * RUPEE, 20_000 * RUPEE);
        }
        return between(random, 20_000 * RUPEE, 100_000 * RUPEE);
    }

    /** A whole number of paise in [low, high]. */
    private static long between(Random random, long low, long high) {
        return low + random.nextLong(high - low + 1);
    }

    private static String randomCustomer(Random random) {
        String handle = LoadCustomers.HANDLES.get(random.nextInt(LoadCustomers.HANDLES.size()));
        return LoadCustomers.vpa(1 + random.nextInt(LoadCustomers.PER_BANK), handle);
    }

    private static String randomPoorCustomer(Random random) {
        String handle = LoadCustomers.HANDLES.get(random.nextInt(LoadCustomers.HANDLES.size()));
        int poorCount = LoadCustomers.PER_BANK / 20;                      // cust020, cust040, ...
        return LoadCustomers.vpa(20 * (1 + random.nextInt(poorCount)), handle);
    }
}

package com.payswitch.switchservice.demo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Phase 13: which payments bring the demo customers back to their starting balances.
 *
 * <p>The rule that makes this safe: a reseed never ADDS money. It only moves money from customers
 * who have more than they started with to customers who have less, as ordinary payments. On the
 * demo stack the six demo customers only ever pay each other, so their total never changes, and
 * the payments put everybody back exactly where they started.
 *
 * <p>Example: Ravi started with Rs 5,000 and paid Anu's shop Rs 3,000 through the day. Ravi is
 * Rs 3,000 short, Anu is Rs 3,000 over, so the plan is one payment: Anu pays Ravi Rs 3,000.
 *
 * <p>Greedy: the biggest surplus pays the biggest shortfall, as much as both allow, until one side
 * is used up. At most (customers - 1) payments. If the totals do not match (money still in transit),
 * it does what it can: afterwards nobody is above their start, or nobody is below it.
 *
 * <p>Pure Java: no Spring, no database, so it can be tested by itself.
 */
public final class ReseedPlan {

    /** One payment of the plan. */
    public record Transfer(String from, String to, long amount) {
    }

    private ReseedPlan() {
    }

    /**
     * @param balances paise each customer has now
     * @param targets  paise each customer should have (the same customers)
     */
    public static List<Transfer> plan(Map<String, Long> balances, Map<String, Long> targets) {
        if (!balances.keySet().equals(targets.keySet())) {
            throw new IllegalArgumentException("balances and targets must name the same customers");
        }
        List<long[]> over = new ArrayList<>();            // {index, surplus}
        List<long[]> under = new ArrayList<>();           // {index, shortfall}
        List<String> names = balances.keySet().stream().sorted().toList();   // same plan every time
        for (int i = 0; i < names.size(); i++) {
            long diff = Math.subtractExact(balances.get(names.get(i)), targets.get(names.get(i)));
            if (diff > 0) {
                over.add(new long[] {i, diff});
            } else if (diff < 0) {
                under.add(new long[] {i, -diff});
            }
        }
        Comparator<long[]> biggestFirst = Comparator.comparingLong((long[] a) -> -a[1]).thenComparingLong(a -> a[0]);
        over.sort(biggestFirst);
        under.sort(biggestFirst);

        List<Transfer> plan = new ArrayList<>();
        int o = 0;
        int u = 0;
        while (o < over.size() && u < under.size()) {
            long amount = Math.min(over.get(o)[1], under.get(u)[1]);
            plan.add(new Transfer(names.get((int) over.get(o)[0]), names.get((int) under.get(u)[0]), amount));
            over.get(o)[1] -= amount;
            under.get(u)[1] -= amount;
            if (over.get(o)[1] == 0) {
                o++;
            }
            if (under.get(u)[1] == 0) {
                u++;
            }
        }
        return plan;
    }
}

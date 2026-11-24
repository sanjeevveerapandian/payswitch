package com.payswitch.switchservice.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Phase 13: the reseed only moves money between customers; it never creates any. */
class ReseedPlanTest {

    private static final Map<String, Long> START = Map.of(
            "ravi@banka", 500_000L, "anu@bankb", 100_000L, "kumar@bankc", 400_000L);

    @Test
    void raviPaidAnuRs3000SoAnuPaysItBack() {
        Map<String, Long> now = Map.of("ravi@banka", 200_000L, "anu@bankb", 400_000L, "kumar@bankc", 400_000L);
        assertEquals(List.of(new ReseedPlan.Transfer("anu@bankb", "ravi@banka", 300_000)),
                ReseedPlan.plan(now, START));
    }

    @Test
    void nothingToDoWhenEveryoneIsAtTheirStart() {
        assertEquals(List.of(), ReseedPlan.plan(START, START));
    }

    @Test
    void oneSurplusCanFillSeveralShortfalls() {
        Map<String, Long> now = Map.of("ravi@banka", 100_000L, "anu@bankb", 800_000L, "kumar@bankc", 100_000L);
        List<ReseedPlan.Transfer> plan = ReseedPlan.plan(now, START);
        assertEquals(List.of(new ReseedPlan.Transfer("anu@bankb", "ravi@banka", 400_000),
                new ReseedPlan.Transfer("anu@bankb", "kumar@bankc", 300_000)), plan);
    }

    @Test
    void customersMustMatch() {
        assertThrows(IllegalArgumentException.class,
                () -> ReseedPlan.plan(Map.of("ravi@banka", 1L), Map.of("anu@bankb", 1L)));
    }

    /**
     * 2,000 random days. After applying the plan: the total is unchanged, nobody is overdrawn, nobody pays
     * more than they have, and either nobody is above their start or nobody is below it (all of them, when
     * the totals match).
     */
    @Test
    void randomDaysNeverCreateMoneyAndGetAsCloseAsPossible() {
        Random random = new Random(42);
        List<String> people = List.of("a@banka", "b@banka", "c@bankb", "d@bankb", "e@bankc", "f@bankc");
        for (int day = 0; day < 2_000; day++) {
            Map<String, Long> start = new HashMap<>();
            Map<String, Long> now = new HashMap<>();
            for (String p : people) {
                start.put(p, (long) random.nextInt(1_000_000));
                now.put(p, (long) random.nextInt(1_000_000));
            }
            if (day % 2 == 0) {                               // half the days: the same total (the demo's case)
                long gap = sum(start) - sum(now);
                now.merge("a@banka", gap, Long::sum);
                if (now.get("a@banka") < 0) {
                    continue;
                }
            }

            Map<String, Long> after = new HashMap<>(now);
            for (ReseedPlan.Transfer t : ReseedPlan.plan(now, start)) {
                assertTrue(t.amount() > 0);
                assertTrue(after.get(t.from()) >= t.amount(), "never pays more than the payer has");
                after.merge(t.from(), -t.amount(), Long::sum);
                after.merge(t.to(), t.amount(), Long::sum);
            }

            assertEquals(sum(now), sum(after), "money only moved");
            boolean someoneAbove = people.stream().anyMatch(p -> after.get(p) > start.get(p));
            boolean someoneBelow = people.stream().anyMatch(p -> after.get(p) < start.get(p));
            assertTrue(!(someoneAbove && someoneBelow), "day " + day + ": " + after + " vs " + start);
            if (sum(now) == sum(start)) {
                assertEquals(start, after, "same total: everybody back exactly");
            }
        }
    }

    private static long sum(Map<String, Long> m) {
        return m.values().stream().mapToLong(Long::longValue).sum();
    }
}

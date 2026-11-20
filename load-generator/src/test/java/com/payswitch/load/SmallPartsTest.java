package com.payswitch.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Percentiles, argument parsing and the "no promise broken" rule. */
class SmallPartsTest {

    @Test
    void percentilesByNearestRank() {
        long[] ms = new long[100];
        for (int i = 0; i < 100; i++) {
            ms[i] = 100 - i;                                   // 100..1, unsorted on purpose
        }
        LatencyStats s = LatencyStats.of(ms);
        assertEquals(new LatencyStats(100, 50, 95, 99, 100), s);
        assertEquals(new LatencyStats(1, 7, 7, 7, 7), LatencyStats.of(new long[] {7}));
        assertEquals(0, LatencyStats.of(new long[0]).count());
    }

    @Test
    void argsParseBothStylesAndRejectTypos() {
        Args a = new Args(new String[] {"--count", "5000", "--chaos=on", "--seed", "9"}, Set.of("count", "chaos", "seed"));
        assertEquals(5000, a.integer("count", 1));
        assertTrue(a.onOff("chaos", false));
        assertEquals(9L, a.longValue("seed", 0));
        assertEquals(50, a.integer("concurrency", 50));
        assertThrows(IllegalArgumentException.class, () -> new Args(new String[] {"--concurrncy", "5"}, Set.of("concurrency")));
        assertThrows(IllegalArgumentException.class, () -> new Args(new String[] {"--count"}, Set.of("count")));
        assertThrows(IllegalArgumentException.class,
                () -> new Args(new String[] {"--chaos", "maybe"}, Set.of("chaos")).onOff("chaos", false));
    }

    @Test
    void aPromiseIsBrokenOnlyWhenAFinalAnswerChanged() {
        List<PaymentResult> results = List.of(
                new PaymentResult(0, "T0", 200, "SUCCESS", "", 5, false),
                new PaymentResult(1, "T1", 200, "PENDING", "", 5, false),     // pending may become anything
                new PaymentResult(2, "T2", 502, "ERROR", "", 5, false),       // no answer: nothing promised
                new PaymentResult(3, "T3", 200, "FAILED", "", 5, false),
                new PaymentResult(4, "T4", 200, "SUCCESS", "", 5, false));
        Map<String, String> finalStates = Map.of("T0", "SUCCESS", "T1", "REVERSED", "T2", "FAILED",
                "T3", "FAILED", "T4", "REVERSED");
        assertEquals(List.of("T4: told SUCCESS, ended REVERSED"), RunReport.brokenPromises(results, finalStates));
        assertFalse(RunReport.brokenPromises(results.subList(0, 4), finalStates).size() > 0);
    }
}

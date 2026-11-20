package com.payswitch.load;

import java.util.Arrays;

/**
 * p50 / p95 / p99 / max of a list of latencies.
 *
 * <p>p99 = 250 ms means 99% of payments took 250 ms or less, as the load generator saw them
 * (round trip through the PSP, switch and banks). Nearest-rank method: sort, then take the value
 * at position ceil(p/100 * n). Exact, and simple enough to check by hand.
 */
public record LatencyStats(int count, long p50Ms, long p95Ms, long p99Ms, long maxMs) {

    public static LatencyStats of(long[] latenciesMs) {
        if (latenciesMs.length == 0) {
            return new LatencyStats(0, 0, 0, 0, 0);
        }
        long[] sorted = latenciesMs.clone();
        Arrays.sort(sorted);
        return new LatencyStats(sorted.length, percentile(sorted, 50), percentile(sorted, 95),
                percentile(sorted, 99), sorted[sorted.length - 1]);
    }

    static long percentile(long[] sorted, int p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);       // 1-based
        return sorted[Math.max(rank, 1) - 1];
    }
}

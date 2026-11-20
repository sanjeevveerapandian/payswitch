package com.payswitch.load;

import com.payswitch.load.LoadGenerator.Config;
import com.payswitch.load.LoadGenerator.LoadResult;
import com.payswitch.load.audit.AuditReport;
import com.payswitch.load.audit.MoneyAuditor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The per-run report: what the load generator saw (counts, TPS, latency), plus the audit.
 *
 * <p>It adds one check the auditor cannot do on its own, because only the load generator knows
 * what each customer was TOLD: "no promise broken". A customer told SUCCESS must end SUCCESS; one
 * told FAILED or REVERSED must end that way. Only PENDING may still turn into anything.
 */
public final class RunReport {

    private RunReport() {
    }

    public static boolean passed(LoadResult load, AuditReport audit) {
        return audit.passed() && brokenPromises(load.results(), audit.finalStates()).isEmpty();
    }

    /** "txnId: told X, ended Y" for every customer who was told something that later changed. */
    public static List<String> brokenPromises(List<PaymentResult> results, Map<String, String> finalStates) {
        List<String> broken = new ArrayList<>();
        for (PaymentResult r : results) {
            if (!r.answered() || "PENDING".equals(r.customerState())) {
                continue;
            }
            String last = finalStates.get(r.txnId());
            if (!r.customerState().equals(last)) {
                broken.add(r.txnId() + ": told " + r.customerState() + ", ended " + last);
            }
        }
        return broken;
    }

    public static String format(String name, Config config, LoadResult load, AuditReport audit) {
        StringBuilder out = new StringBuilder();
        out.append("=== Run ").append(name).append(" ===\n");
        out.append(String.format("Payments: %d, concurrency %d, seed %d, chaos %s, switch crash %s%n",
                config.count(), config.concurrency(), config.seed(), config.chaos() ? "ON" : "off",
                config.crashSwitchAtPercent() == null ? "none" : "at " + config.crashSwitchAtPercent() + "%"));
        double secs = load.nanos() / 1e9;
        out.append(String.format("Load: %.1f s, %.0f payments/s (TPS, as sent by %d concurrent clients)%n",
                secs, load.tps(), config.concurrency()));

        Map<String, Integer> told = new TreeMap<>();
        Map<Integer, Integer> http = new TreeMap<>();
        List<Long> all = new ArrayList<>();
        List<Long> answered = new ArrayList<>();
        int pendingThenSuccess = 0;
        int pendingThenReversed = 0;
        int pendingThenFailed = 0;
        int neverReachedSwitch = 0;
        for (PaymentResult r : load.results()) {
            told.merge(r.customerState(), 1, Integer::sum);
            http.merge(r.httpStatus(), 1, Integer::sum);
            all.add(r.latencyMs());
            if (r.answered()) {
                answered.add(r.latencyMs());
            }
            if (audit != null) {
                String last = r.txnId() == null ? null : audit.finalStates().get(r.txnId());
                if (last == null) {
                    neverReachedSwitch++;
                } else if ("PENDING".equals(r.customerState()) || !r.answered()) {
                    switch (last) {
                        case "SUCCESS" -> pendingThenSuccess++;
                        case "REVERSED" -> pendingThenReversed++;
                        default -> pendingThenFailed++;
                    }
                }
            }
        }
        out.append("HTTP status from the PSP (-1 = no answer at all): ").append(http).append('\n');
        out.append("Customer was told: ").append(told).append('\n');
        out.append("Latency, all requests:   ").append(latency(all)).append('\n');
        out.append("Latency, answered (200): ").append(latency(answered)).append('\n');
        if (!load.crashLog().isEmpty()) {
            out.append("Crash:\n");
            load.crashLog().forEach(l -> out.append("  ").append(l).append('\n'));
        }
        if (audit == null) {
            out.append("(not audited)\n");
            return out.toString();
        }
        out.append(String.format("Told PENDING or no answer, finished later: %d SUCCESS, %d REVERSED, %d FAILED; "
                + "%d never reached the switch (no money moved)%n", pendingThenSuccess, pendingThenReversed,
                pendingThenFailed, neverReachedSwitch));
        List<String> broken = brokenPromises(load.results(), audit.finalStates());
        out.append(broken.isEmpty() ? "PASS" : "FAIL").append("  8) no customer was told something that later "
                + "changed: ").append(broken.size()).append(" broken\n");
        broken.stream().limit(20).forEach(b -> out.append("      ").append(b).append('\n'));
        out.append('\n').append(MoneyAuditor.format(audit));
        Duration total = Duration.ofNanos(load.nanos()).plus(audit.prepareTime()).plus(audit.checkTime());
        out.append(String.format("Total runtime (load + audit): %.1f s%n", total.toMillis() / 1000.0));
        out.append("RESULT: ").append(passed(load, audit) ? "PASS" : "FAIL").append('\n');
        return out.toString();
    }

    private static String latency(List<Long> ms) {
        LatencyStats s = LatencyStats.of(ms.stream().mapToLong(Long::longValue).toArray());
        return String.format("p50 %d ms, p95 %d ms, p99 %d ms, max %d ms (n=%d)", s.p50Ms(), s.p95Ms(), s.p99Ms(),
                s.maxMs(), s.count());
    }
}

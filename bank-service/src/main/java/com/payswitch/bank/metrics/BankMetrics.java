package com.payswitch.bank.metrics;

import com.payswitch.common.bank.BankResponse;
import com.payswitch.common.bank.DeclineReason;
import com.payswitch.common.bank.OpType;
import com.payswitch.observability.AfterCommit;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Every metric the bank publishes. All are COUNTERS (they only go up; Prometheus turns them into
 * rates). Labels only take fixed values: an operation, a result, a chaos mode. Never a txnId, VPA
 * or amount. Which bank it is comes from the common tag {@code bank} (management.metrics.tags).
 *
 * <p>Counted after commit: an operation whose transaction rolled back did not happen.
 */
@Component
public class BankMetrics {

    private final MeterRegistry registry;

    public BankMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Start the rare counters at 0. A series that first appears with value 1 makes rate() and
        // increase() miss that first event, and a dashboard shows "No data" instead of a flat 0.
        for (OpType op : OpType.values()) {
            replays(op.name());
            fenced(op.name());
        }
    }

    private Counter replays(String op) {
        return Counter.builder("bank.idempotent.replays")
                .description("Repeated requests answered from the saved response (no money moved)")
                .tag("op", op)
                .register(registry);
    }

    private Counter fenced(String op) {
        return Counter.builder("bank.fenced.rejections")
                .description("Requests refused because a status check or reversal fenced them")
                .tag("op", op)
                .register(registry);
    }

    /** For unit tests that build classes by hand. */
    public static BankMetrics inMemory() {
        return new BankMetrics(new SimpleMeterRegistry());
    }

    /**
     * An operation that actually ran (first time for this txnId and op).
     *
     * @param op DEBIT, CREDIT, REVERSE or STATUS_CHECK
     */
    public void processed(String op, String result) {
        AfterCommit.run(() -> Counter.builder("bank.ops")
                .description("Operations processed by this bank, by result")
                .tag("op", op)
                .tag("result", result)
                .register(registry)
                .increment());
    }

    /** SUCCESS, NO_DEBIT, or DECLINED_ plus the reason (a short fixed list). */
    public static String resultOf(BankResponse r) {
        return switch (r.result()) {
            case SUCCESS, NO_DEBIT -> r.result().name();
            case DECLINED -> "DECLINED_" + (r.reason() == null ? "UNSPECIFIED" : r.reason().name());
        };
    }

    /**
     * A request for a (txnId, op) we had already answered: the saved answer was sent again and no
     * money moved. A tombstone answer (the op was fenced, or its debit already reversed) is counted
     * as a fenced rejection instead: that one stopped a late request that must never run.
     */
    public void answeredFromSaved(String op, BankResponse saved) {
        boolean isFence = saved.reason() == DeclineReason.FENCED || saved.reason() == DeclineReason.ALREADY_REVERSED;
        AfterCommit.run(() -> (isFence ? fenced(op) : replays(op)).increment());
    }

    /** @param mode decline, error, drop_response, status_error or delay */
    public void chaosInjected(String mode) {
        Counter.builder("bank.chaos.injected")
                .description("Failures injected on purpose by the chaos engine")
                .tag("mode", mode)
                .register(registry)
                .increment();
    }
}

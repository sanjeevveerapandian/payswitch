package com.payswitch.switchservice.metrics;

import com.payswitch.observability.AfterCommit;
import com.payswitch.switchservice.bank.BankCallOutcome;
import com.payswitch.switchservice.domain.TxnState;
import com.payswitch.switchservice.domain.TxnStateMachine;
import com.payswitch.switchservice.recon.ReconCategory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Every metric the switch publishes, in one place, so the label rule is easy to check:
 *
 * <p><b>Labels only ever take a small, fixed set of values</b>: a state, a bank code, an operation,
 * an outcome, a recon category. Never a txnId, a VPA or an amount. Each distinct combination of
 * label values is a separate time series that Prometheus stores forever; a txnId label would create
 * a new series for every payment (1 lakh payments = 1 lakh series) and take Prometheus down.
 *
 * <p>Micrometer names use dots; Prometheus shows them with underscores, and adds "_total" to
 * counters and "_seconds" to timers: {@code payswitch.payments} becomes {@code payswitch_payments_total}.
 */
@Component
public class SwitchMetrics {

    private final MeterRegistry registry;
    /** Latest net position per bank, from the last settled cycle. Gauges read these. */
    private final Map<String, AtomicLong> settlementNet = new ConcurrentHashMap<>();

    public SwitchMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Start every final state and recon category at 0: a series that first appears with value 1
        // makes rate() and increase() miss that first event (and an alert on it would never fire).
        for (TxnState state : TxnState.values()) {
            if (TxnStateMachine.isTerminal(state)) {
                payments(state);
            }
        }
        for (ReconCategory category : ReconCategory.values()) {
            reconItems(category);
        }
    }

    private Counter payments(TxnState finalState) {
        return Counter.builder("payswitch.payments")
                .description("Payments that reached a final state")
                .tag("final_state", finalState.name())
                .register(registry);
    }

    private Counter reconItems(ReconCategory category) {
        return Counter.builder("payswitch.recon.items")
                .description("Reconciled txnIds by category")
                .tag("category", category.name())
                .register(registry);
    }

    /** For unit tests that build classes by hand: real meters, kept in memory, read by nobody. */
    public static SwitchMetrics inMemory() {
        return new SwitchMetrics(new SimpleMeterRegistry());
    }

    public MeterRegistry registry() {
        return registry;
    }

    /**
     * COUNTER: a payment reached a final state. Counted only after the transaction that saved the
     * state has committed, so a rolled-back change is never counted.
     */
    public void paymentFinished(TxnState finalState) {
        AfterCommit.run(() -> payments(finalState).increment());
    }

    /**
     * HISTOGRAM (a Timer with buckets): how long one call to a bank took. Buckets let Prometheus work
     * out p50/p95/p99 across all switch instances with histogram_quantile().
     *
     * @param op DEBIT, CREDIT, REVERSE or STATUS_CHECK
     */
    public void bankCall(String bank, String op, BankCallOutcome outcome, long nanos) {
        Timer.builder("payswitch.bank.call")
                .description("Time for one switch -> bank HTTP call")
                .tag("bank", bank)
                .tag("op", op)
                .tag("outcome", outcome.name())
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(10))
                .register(registry)
                .record(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    /**
     * COUNTER: what a status check found out.
     *
     * @param result FOUND_SUCCESS, FOUND_DECLINED, FENCED or UNKNOWN
     */
    public void statusCheck(String bank, String result) {
        Counter.builder("payswitch.status.checks")
                .description("Status checks sent to banks, by what they found")
                .tag("bank", bank)
                .tag("result", result)
                .register(registry)
                .increment();
    }

    /** COUNTER: extra attempts (the first attempt is not a retry). */
    public void retries(String bank, String op, int extraAttempts) {
        if (extraAttempts > 0) {
            Counter.builder("payswitch.retries")
                    .description("Retried calls to banks (status checks and reversals)")
                    .tag("bank", bank)
                    .tag("op", op)
                    .register(registry)
                    .increment(extraAttempts);
        }
    }

    /** COUNTER: the resolution logic moved a payment on from a state it had got stuck in. */
    public void resolverFixed(TxnState fromState) {
        Counter.builder("payswitch.resolver.fixed")
                .description("Payments moved on by the resolver, by the state they were stuck in")
                .tag("from_state", fromState.name())
                .register(registry)
                .increment();
    }

    /** COUNTER: one txnId reconciled, by its final category. */
    public void reconItem(ReconCategory category) {
        reconItems(category).increment();
    }

    /** GAUGE: the bank's net position in the last settled cycle (paise; negative = it paid in). */
    public void settlementNet(String bank, long paise) {
        settlementNet.computeIfAbsent(bank, b -> {
            AtomicLong value = new AtomicLong();
            Gauge.builder("payswitch.settlement.net.amount.paise", value, AtomicLong::get)
                    .description("Net position per bank in the last settled cycle, in paise")
                    .tag("bank", b)
                    .register(registry);
            return value;
        }).set(paise);
    }
}

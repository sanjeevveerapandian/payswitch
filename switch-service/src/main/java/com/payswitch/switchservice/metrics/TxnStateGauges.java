package com.payswitch.switchservice.metrics;

import com.payswitch.switchservice.domain.TxnState;
import com.payswitch.switchservice.domain.TxnStateMachine;
import com.payswitch.switchservice.service.ResolutionProperties;
import io.micrometer.core.instrument.Gauge;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * GAUGES: how many payments sit in each unfinished state right now, and how many of those have
 * been sitting there longer than {@code switch.resolution.stuck-after}.
 *
 * <p>A gauge is a value that goes up AND down, read at the moment Prometheus scrapes. Counting rows
 * on every scrape would put load on the database whenever anyone opens a dashboard, so instead one
 * cheap query runs every few seconds (it only touches the unfinished states, through the
 * (state, updated_at) index) and the gauges just read the last numbers.
 */
@Component
public class TxnStateGauges {

    private static final Logger log = LoggerFactory.getLogger(TxnStateGauges.class);

    /** INITIATED, DEBIT_SENT, DEBITED, CREDIT_SENT, REVERSAL_SENT, DEEMED_APPROVED. */
    static final List<TxnState> UNFINISHED = Arrays.stream(TxnState.values())
            .filter(s -> !TxnStateMachine.isTerminal(s)).toList();

    private final JdbcClient jdbc;
    private final ResolutionProperties resolution;
    private final Map<TxnState, AtomicLong> inState = new EnumMap<>(TxnState.class);
    private final Map<TxnState, AtomicLong> stuck = new EnumMap<>(TxnState.class);

    public TxnStateGauges(JdbcClient jdbc, ResolutionProperties resolution, SwitchMetrics metrics) {
        this.jdbc = jdbc;
        this.resolution = resolution;
        // Registered up front for every state, so a state with no payments shows 0, not "no data".
        for (TxnState state : UNFINISHED) {
            inState.put(state, register(metrics, "payswitch.txns.in.state",
                    "Payments currently in this unfinished state", state));
            stuck.put(state, register(metrics, "payswitch.txns.stuck",
                    "Payments in this state for longer than switch.resolution.stuck-after", state));
        }
    }

    private static AtomicLong register(SwitchMetrics metrics, String name, String description, TxnState state) {
        AtomicLong value = new AtomicLong();
        Gauge.builder(name, value, AtomicLong::get)
                .description(description)
                .tag("state", state.name())
                .register(metrics.registry());
        return value;
    }

    @Scheduled(initialDelayString = "${switch.metrics.state-count-interval:15s}",
            fixedDelayString = "${switch.metrics.state-count-interval:15s}")
    public void refresh() {
        try {
            Map<TxnState, long[]> counts = new EnumMap<>(TxnState.class);
            jdbc.sql("""
                    SELECT state,
                           COUNT(*) AS total,
                           COUNT(*) FILTER (WHERE updated_at <= now() - make_interval(secs => :stuckFor)) AS stuck
                    FROM transactions
                    WHERE state IN (:states)
                    GROUP BY state
                    """)
                    .param("stuckFor", resolution.stuckAfter().toMillis() / 1000.0)
                    .param("states", UNFINISHED.stream().map(Enum::name).toList())
                    .query((rs, n) -> counts.put(TxnState.valueOf(rs.getString("state")),
                            new long[] {rs.getLong("total"), rs.getLong("stuck")}))
                    .list();
            for (TxnState state : UNFINISHED) {
                long[] c = counts.getOrDefault(state, new long[] {0, 0});
                inState.get(state).set(c[0]);
                stuck.get(state).set(c[1]);
            }
        } catch (RuntimeException e) {
            // Keep the last numbers; a metrics query must never break the switch.
            log.warn("Could not count payments per state: {}", e.toString());
        }
    }
}

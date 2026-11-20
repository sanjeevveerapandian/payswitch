package com.payswitch.load;

import com.fasterxml.jackson.databind.JsonNode;
import com.payswitch.load.PaymentPlan.Planned;
import com.payswitch.load.audit.AuditConfig;
import com.payswitch.load.audit.AuditReport;
import com.payswitch.load.audit.MoneyAuditor;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Sends many payments through the PSP, as fast as the concurrency limit allows, then (by default)
 * runs the {@link MoneyAuditor} and prints one report.
 *
 * <pre>
 *   java -jar load-generator/target/load-generator-0.1.0-SNAPSHOT-all.jar \
 *        --count 100000 --concurrency 50 --seed 42 --chaos on --crash-switch-at 50
 * </pre>
 *
 * <p><b>Virtual threads.</b> Every payment runs on its own virtual thread. A virtual thread is a
 * thread managed by the JVM, not the operating system: it costs a few hundred bytes instead of
 * ~1 MB of stack, so a million of them is fine. When one blocks on I/O (waiting 200 ms for the PSP),
 * the JVM parks it and runs another on the same OS "carrier" thread. So we can write simple
 * blocking code (send, wait, read the answer) and still keep thousands of requests in flight, with
 * no callbacks and no reactive library. The {@link Semaphore} is what limits us to --concurrency
 * payments in flight: cheap threads do not make the PSP's capacity infinite.
 */
public final class LoadGenerator {

    /** Run B's chaos, on every bank: 5% declines, 3% HTTP 500, 2% dropped answers, 5% failed
     *  status checks, and a random 0-300 ms delay on every call. */
    public static final Map<String, Object> CHAOS = Map.of(
            "declineRate", 0.05, "errorRate", 0.03, "dropResponseRate", 0.02,
            "statusErrorRate", 0.05, "delayMs", 0, "delayJitterMs", 300);

    /**
     * @param crashSwitchAtPercent null = never crash
     * @param backoffAfterError    a worker that got no clean answer waits this long before its next
     *                             payment, like a real app would. Without it, 50 workers would burn
     *                             through thousands of payments per second while the switch is down.
     */
    public record Config(int count, int concurrency, long seed, boolean chaos, Integer crashSwitchAtPercent,
                         String pspUrl, List<String> bankUrls, SwitchCrasher crasher, Duration requestTimeout,
                         Duration backoffAfterError) {

        public Config {
            if (count < 0 || concurrency < 1) {
                throw new IllegalArgumentException("count must be >= 0 and concurrency >= 1");
            }
            if (crashSwitchAtPercent != null && (crashSwitchAtPercent < 0 || crashSwitchAtPercent > 100)) {
                throw new IllegalArgumentException("--crash-switch-at must be 0..100");
            }
        }
    }

    /** Everything the run produced. results.get(i) is planned payment i. */
    public record LoadResult(List<PaymentResult> results, Instant start, Instant end, long nanos,
                             List<String> crashLog) {

        public double tps() {
            return nanos == 0 ? 0 : results.size() * 1e9 / nanos;
        }
    }

    private final Config config;
    private final Http http = new Http(Duration.ofSeconds(2));
    private final Consumer<String> log;

    public LoadGenerator(Config config, Consumer<String> log) {
        this.config = config;
        this.log = log;
    }

    // ---- main -----------------------------------------------------------------------------------

    public static void main(String[] argv) throws Exception {
        Args a = new Args(argv, Set.of("count", "concurrency", "seed", "chaos", "crash-switch-at", "psp-url",
                "bank-urls", "compose-dir", "audit", "switch-url", "db-url", "db-user", "db-password", "report",
                "name", "audit-timeout-seconds"));
        Config config = new Config(
                a.integer("count", 100_000), a.integer("concurrency", 50), a.longValue("seed", 42),
                a.onOff("chaos", false), a.has("crash-switch-at") ? a.integer("crash-switch-at", 50) : null,
                a.text("psp-url", "http://localhost:8080"),
                List.of(a.text("bank-urls", "http://localhost:8081,http://localhost:8082,http://localhost:8084").split(",")),
                SwitchCrasher.dockerCompose(new File(a.text("compose-dir", ".")), 5_000),
                Duration.ofSeconds(30), Duration.ofMillis(200));
        Consumer<String> log = line -> System.out.println("[" + Instant.now() + "] " + line);

        AuditConfig d = AuditConfig.dockerDefaults();
        AuditConfig auditConfig = new AuditConfig(a.text("switch-url", d.switchUrl()), a.text("db-url", d.jdbcBase()),
                a.text("db-user", d.user()), a.text("db-password", d.password()), d.bankDatabases(), d.switchDatabase(),
                true, Duration.ofSeconds(a.longValue("audit-timeout-seconds", d.waitTimeout().toSeconds())),
                d.expectedTotal(), d.reconCutoff());
        boolean audit = a.onOff("audit", true);
        MoneyAuditor before = new MoneyAuditor(auditConfig, log);
        long startTotal = audit ? before.totalCustomerMoney() : 0;
        if (audit) {
            log.accept("Total customer money before the run: " + Rupees.rs(startTotal));
        }

        LoadGenerator generator = new LoadGenerator(config, log);
        LoadResult load = generator.runWithChaos();

        AuditReport report = null;
        if (audit) {
            log.accept("Load done. Auditing (chaos is off now)...");
            report = new MoneyAuditor(auditConfig.withExpectedTotal(startTotal), log).audit();
        }
        String text = RunReport.format(a.text("name", "run"), config, load, report);
        System.out.println();
        System.out.println(text);
        if (a.has("report")) {
            Files.writeString(Path.of(a.text("report", "")), text);
        }
        System.exit(report == null || RunReport.passed(load, report) ? 0 : 1);
    }

    // ---- the run --------------------------------------------------------------------------------

    /** Chaos on (if asked), the run, and chaos OFF again whatever happens. */
    public LoadResult runWithChaos() throws Exception {
        waitUntilPspIsUp(Duration.ofMinutes(3));
        if (config.chaos()) {
            setChaos(true);
        }
        try {
            return run();
        } finally {
            if (config.chaos()) {
                setChaos(false);
            }
        }
    }

    public LoadResult run() throws InterruptedException {
        List<Planned> plan = PaymentPlan.generate(config.seed(), config.count());
        PaymentResult[] results = new PaymentResult[plan.size()];
        List<String> crashLog = Collections.synchronizedList(new ArrayList<>());
        Consumer<String> crashLogger = line -> {
            String stamped = Instant.now() + "  " + line;
            crashLog.add(stamped);
            log.accept(line);
        };
        int crashAt = config.crashSwitchAtPercent() == null ? -1
                : (int) ((long) plan.size() * config.crashSwitchAtPercent() / 100);
        Semaphore inFlight = new Semaphore(config.concurrency());
        AtomicInteger done = new AtomicInteger();
        int progressEvery = Math.max(1, plan.size() / 20);                 // every 5%

        log.accept("Sending " + plan.size() + " payments, " + config.concurrency() + " at a time, seed "
                + config.seed() + ", chaos " + (config.chaos() ? "ON" : "off")
                + (crashAt >= 0 ? ", switch crash at payment " + crashAt : ""));
        Instant start = Instant.now();
        long t0 = System.nanoTime();
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Planned p : plan) {
                inFlight.acquire();                         // blocks while `concurrency` payments are in flight
                if (p.index() == crashAt) {
                    threads.submit(() -> {
                        try {
                            config.crasher().crashAndRestart(crashLogger);
                        } catch (Exception e) {
                            crashLogger.accept("CRASH FAILED: " + e);
                        }
                        return null;
                    });
                }
                threads.submit(() -> {
                    try {
                        results[p.index()] = send(p);
                    } finally {
                        inFlight.release();
                        int n = done.incrementAndGet();
                        if (n % progressEvery == 0) {
                            double secs = (System.nanoTime() - t0) / 1e9;
                            log.accept(String.format("%d / %d done (%.0f%%), %.0f payments/s", n, plan.size(),
                                    100.0 * n / plan.size(), n / secs));
                        }
                    }
                });
            }
        }                                                   // close() waits for every payment (and the crash)
        long nanos = System.nanoTime() - t0;
        return new LoadResult(Arrays.asList(results), start, Instant.now(), nanos, List.copyOf(crashLog));
    }

    /** One payment. Never throws: every problem becomes a result, and the run keeps going. */
    PaymentResult send(Planned p) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("payerVpa", p.payerVpa());
        body.put("payeeVpa", p.payeeVpa());
        body.put("amount", Rupees.text(p.paise()));
        long t0 = System.nanoTime();
        PaymentResult result;
        try {
            Http.Response r = http.post(config.pspUrl() + "/pay", body, config.requestTimeout());
            long ms = (System.nanoTime() - t0) / 1_000_000;
            JsonNode json = r.json();
            String txnId = json.hasNonNull("txnId") ? json.get("txnId").asText() : null;
            if (r.status() == 200) {
                result = new PaymentResult(p.index(), txnId, 200, json.path("state").asText("?"),
                        json.path("reason").asText(""), ms, p.overdraftAttempt());
            } else {
                result = new PaymentResult(p.index(), txnId, r.status(), "ERROR",
                        json.path("detail").asText(r.body()), ms, p.overdraftAttempt());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new PaymentResult(p.index(), null, -1, "ERROR", "interrupted", 0, p.overdraftAttempt());
        } catch (Exception e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            result = new PaymentResult(p.index(), null, -1, "ERROR", e.getClass().getSimpleName() + ": "
                    + e.getMessage(), ms, p.overdraftAttempt());
        }
        if (result.httpStatus() != 200) {
            pause(config.backoffAfterError());
        }
        return result;
    }

    /**
     * Do not start timing until the PSP answers its health check: payments sent while it is still
     * booting would only measure "connection refused", not the payment system.
     */
    void waitUntilPspIsUp(Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            try {
                if (http.get(config.pspUrl() + "/actuator/health", Duration.ofSeconds(5)).ok()) {
                    return;
                }
            } catch (java.io.IOException notYet) {
                // still starting
            }
            Thread.sleep(1_000);
        }
        log.accept("PSP still not healthy after " + timeout + "; starting anyway");
    }

    /** Chaos on or off on every bank. */
    public void setChaos(boolean on) throws Exception {
        for (String bank : config.bankUrls()) {
            http.postOk(bank.trim() + "/admin/chaos", on ? CHAOS : Map.of(), Duration.ofSeconds(10));
        }
        log.accept("Chaos " + (on ? "ON " + CHAOS : "OFF") + " on " + config.bankUrls());
    }

    private static void pause(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

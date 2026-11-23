package com.payswitch.psp;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The demo's "Run 1,000 payments" button: random payments between the six seeded customers, sent
 * through {@link PayService} (exactly like POST /pay) from virtual threads, at most
 * {@link #CONCURRENCY} at a time. One run at a time; it runs in the background and the page polls
 * {@link #status()} for live counters.
 *
 * <p>The big Phase 10 load generator is a separate command-line tool that needs the 900 load-test
 * customers; this one is small on purpose and works with the normal demo data.
 */
@Service
public class LoadRunner {

    private static final Logger log = LoggerFactory.getLogger(LoadRunner.class);

    /** The seeded customers (V100 seed migrations). */
    static final List<String> CUSTOMERS = List.of("ravi@banka", "meena@banka", "anu@bankb", "kiran@bankb",
            "kumar@bankc", "priya@bankc");
    static final int CONCURRENCY = 10;
    static final int MAX_COUNT = 10_000;

    /** What the page shows. byResult: SUCCESS, FAILED, REVERSED, PENDING, SWITCH_UNAVAILABLE, ... */
    public record Status(boolean running, int requested, int sent, int done, Map<String, Integer> byResult,
                         Instant startedAt, Instant finishedAt) {
    }

    private final PayService payments;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger requested = new AtomicInteger();
    private final AtomicInteger sent = new AtomicInteger();
    private final AtomicInteger done = new AtomicInteger();
    private final Map<String, AtomicInteger> byResult = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> startedAt = new AtomicReference<>();
    private final AtomicReference<Instant> finishedAt = new AtomicReference<>();

    public LoadRunner(PayService payments) {
        this.payments = payments;
    }

    /** @return false if a run is already going (nothing new is started) */
    public boolean start(int count) {
        if (count < 1 || count > MAX_COUNT) {
            throw new IllegalArgumentException("count must be 1 to " + MAX_COUNT);
        }
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        requested.set(count);
        sent.set(0);
        done.set(0);
        byResult.clear();
        startedAt.set(Instant.now());
        finishedAt.set(null);
        Thread.ofPlatform().daemon().name("demo-load").start(() -> run(count));
        return true;
    }

    private void run(int count) {
        Semaphore inFlight = new Semaphore(CONCURRENCY);
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < count; i++) {
                inFlight.acquire();                            // never more than CONCURRENCY at once
                sent.incrementAndGet();
                threads.submit(() -> {
                    try {
                        count(payOnce());
                    } finally {
                        done.incrementAndGet();
                        inFlight.release();
                    }
                });
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            finishedAt.set(Instant.now());
            running.set(false);
            log.info("Demo load finished: {} payments in {}", done.get(),
                    Duration.between(startedAt.get(), finishedAt.get()));
        }
    }

    /** One random payment. Never throws: every outcome becomes a counter. */
    private String payOnce() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String payer = CUSTOMERS.get(random.nextInt(CUSTOMERS.size()));
        String payee;
        do {
            payee = CUSTOMERS.get(random.nextInt(CUSTOMERS.size()));
        } while (payee.equals(payer));
        String rupees = (1 + random.nextInt(200)) + ".00";             // Rs 1 to Rs 200, whole rupees
        try {
            return payments.pay(new PayRequest(payer, payee, rupees)).state().name();
        } catch (SwitchUnavailableException e) {
            return "SWITCH_UNAVAILABLE";
        } catch (RuntimeException e) {
            return "ERROR";
        }
    }

    private void count(String result) {
        byResult.computeIfAbsent(result, k -> new AtomicInteger()).incrementAndGet();
    }

    public Status status() {
        Map<String, Integer> counts = new TreeMap<>();
        byResult.forEach((k, v) -> counts.put(k, v.get()));
        return new Status(running.get(), requested.get(), sent.get(), done.get(), counts, startedAt.get(),
                finishedAt.get());
    }
}

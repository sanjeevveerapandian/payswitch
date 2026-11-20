package com.payswitch.load;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The load generator against a FAKE PSP (the JDK's built-in HTTP server): no Docker needed.
 * Checks the concurrency limit, that errors are recorded and the run keeps going, and the crash hook.
 */
class LoadGeneratorTest {

    private HttpServer fake;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger healthChecks = new AtomicInteger();
    private final List<String> chaosBodies = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> seenTxn = new ConcurrentHashMap<>();

    @BeforeEach
    void startFakePsp() throws IOException {
        fake = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        fake.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        fake.createContext("/pay", this::pay);
        // "Still booting" twice, then healthy: the generator must wait instead of sending.
        fake.createContext("/actuator/health", ex -> {
            boolean up = healthChecks.incrementAndGet() > 2;
            reply(ex, up ? 200 : 503, up ? "{\"status\":\"UP\"}" : "{}");
        });
        fake.createContext("/admin/chaos", ex -> {
            chaosBodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(ex, 200, "{}");
        });
        fake.start();
    }

    @AfterEach
    void stop() {
        fake.stop(0);
    }

    /** Every 10th call: 502 (switch down) with a txnId. Every 25th: connection cut, no answer. */
    private void pay(HttpExchange ex) throws IOException {
        int now = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(now, Math::max);
        try {
            JsonNode body = Http.JSON.readTree(ex.getRequestBody());
            assertNotNull(body.get("amount").asText());
            Thread.sleep(15);
            int n = calls.incrementAndGet();
            String txnId = "TXN" + n;
            seenTxn.put(txnId, n);
            if (n % 25 == 0) {
                ex.close();                                   // no HTTP answer at all
            } else if (n % 10 == 0) {
                reply(ex, 502, "{\"detail\":\"Switch unavailable\",\"txnId\":\"" + txnId + "\"}");
            } else {
                reply(ex, 200, "{\"txnId\":\"" + txnId + "\",\"state\":\"SUCCESS\",\"reason\":\"CREDIT_OK\"}");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private static void reply(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private String url() {
        return "http://localhost:" + fake.getAddress().getPort();
    }

    @Test
    void sendsEveryPaymentWithinTheConcurrencyLimitAndKeepsGoingOnErrors() throws Exception {
        AtomicInteger crashes = new AtomicInteger();
        SwitchCrasher fakeCrash = log -> {
            crashes.incrementAndGet();
            log.accept("CRASH: pretend");
            log.accept("RESTART: pretend");
        };
        LoadGenerator.Config config = new LoadGenerator.Config(400, 8, 3, true, 50, url(), List.of(url()),
                fakeCrash, Duration.ofSeconds(5), Duration.ofMillis(1));
        LoadGenerator.LoadResult result = new LoadGenerator(config, line -> { }).runWithChaos();

        assertEquals(3, healthChecks.get(), "waited until the PSP was healthy");
        assertEquals(400, result.results().size());
        result.results().forEach(r -> assertNotNull(r, "every planned payment has a result"));
        assertTrue(maxInFlight.get() <= 8, "never more than 8 in flight, saw " + maxInFlight.get());
        assertTrue(maxInFlight.get() >= 4, "actually concurrent, saw " + maxInFlight.get());

        long ok = result.results().stream().filter(PaymentResult::answered).count();
        long badGateway = result.results().stream().filter(r -> r.httpStatus() == 502).count();
        long noAnswer = result.results().stream().filter(r -> r.httpStatus() == -1).count();
        assertEquals(400, ok + badGateway + noAnswer);
        assertEquals(16, noAnswer);                       // calls 25, 50, ... 400
        assertEquals(32, badGateway);                     // 40 multiples of 10, minus 8 that are also multiples of 25
        result.results().stream().filter(r -> r.httpStatus() == 502)
                .forEach(r -> assertNotNull(r.txnId(), "the 502 body's txnId is kept, to look it up later"));

        assertEquals(1, crashes.get());
        assertEquals(2, result.crashLog().size());
        // Chaos was switched on before the run and off after it.
        assertEquals(2, chaosBodies.size());
        assertTrue(chaosBodies.get(0).contains("\"dropResponseRate\":0.02"), chaosBodies.get(0));
        assertEquals("{}", chaosBodies.get(1));
        assertTrue(result.tps() > 0);
    }
}

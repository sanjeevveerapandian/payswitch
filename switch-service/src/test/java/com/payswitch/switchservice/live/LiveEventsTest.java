package com.payswitch.switchservice.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payswitch.common.pay.ReqPay;
import com.payswitch.switchservice.TestcontainersConfig;
import com.payswitch.switchservice.bank.BankCallResult;
import com.payswitch.switchservice.bank.BankClient;
import com.payswitch.switchservice.domain.TxnState;
import com.payswitch.switchservice.service.PaymentOrchestrator;
import com.payswitch.switchservice.service.TransactionService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Phase 12: GET /events/txns over real HTTP. A test client reads the SSE stream line by line, the
 * way a browser's EventSource does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "switch.resolution.enabled=false",
        "switch.recon.enabled=false",
        "switch.settlement.enabled=false",
        "switch.live.batch-interval=100ms",
})
@Import(TestcontainersConfig.class)
class LiveEventsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @MockitoBean
    private BankClient banks;

    @Autowired
    private PaymentOrchestrator orchestrator;

    @Autowired
    private TransactionService txns;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private TxnEventStream stream;

    private final List<Stream<String>> open = new ArrayList<>();

    @AfterEach
    void closeStreams() {
        open.forEach(Stream::close);
    }

    /** One SSE event as the browser sees it. */
    record Sse(String name, JsonNode data) {
    }

    /** Subscribes and returns a queue that fills with every event (heartbeat comments are skipped). */
    private BlockingQueue<Sse> subscribe() throws Exception {
        HttpResponse<Stream<String>> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/events/txns"))
                        .header("Accept", "text/event-stream").build(),
                HttpResponse.BodyHandlers.ofLines());
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
        Stream<String> lines = response.body();
        open.add(lines);
        BlockingQueue<Sse> events = new LinkedBlockingQueue<>();
        Thread.ofVirtual().start(() -> {
            String name = null;
            try {
                for (String line : (Iterable<String>) lines::iterator) {
                    if (line.startsWith("event:")) {
                        name = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        events.add(new Sse(name, JSON.readTree(line.substring(5))));
                    }
                }
            } catch (Exception closed) {
                // the test closed the stream
            }
        });
        return events;
    }

    /** Collects the states of {@code txnId}, in arrival order, until {@code done} says stop. */
    private static List<String> statesOf(BlockingQueue<Sse> events, String txnId, Predicate<List<String>> done)
            throws InterruptedException {
        return statesOf(events, txnId, done, new ArrayList<>());
    }

    /** Same, and also keeps EVERY change that arrived meanwhile (for any txnId) in {@code seen}. */
    private static List<String> statesOf(BlockingQueue<Sse> events, String txnId, Predicate<List<String>> done,
                                         List<JsonNode> seen) throws InterruptedException {
        List<String> states = new ArrayList<>();
        Instant deadline = Instant.now().plusSeconds(10);
        while (!done.test(states) && Instant.now().isBefore(deadline)) {
            Sse e = events.poll(200, TimeUnit.MILLISECONDS);
            if (e != null && "txns".equals(e.name())) {
                e.data().forEach(change -> {
                    seen.add(change);
                    if (change.get("txnId").asText().equals(txnId)) {
                        states.add(change.get("to").asText());
                    }
                });
            }
        }
        return states;
    }

    private static Sse first(BlockingQueue<Sse> events) throws InterruptedException {
        Sse e = events.poll(5, TimeUnit.SECONDS);
        assertNotNull(e, "nothing arrived");
        return e;
    }

    private static String newTxnId() {
        return "TXN-" + UUID.randomUUID();
    }

    @Test
    void aPaymentsStateChangesArriveLiveAndInOrder() throws Exception {
        BlockingQueue<Sse> events = subscribe();
        assertEquals("snapshot", first(events).name(), "the snapshot always comes first");
        when(banks.debit(any(), any())).thenReturn(BankCallResult.success());
        when(banks.credit(any(), any())).thenReturn(BankCallResult.success());
        String txnId = newTxnId();

        orchestrator.pay(new ReqPay(txnId, "ravi@banka", "anu@bankb", 20_000));

        assertEquals(List.of("INITIATED", "DEBIT_SENT", "DEBITED", "CREDIT_SENT", "SUCCESS"),
                statesOf(events, txnId, s -> s.contains("SUCCESS")));
    }

    @Test
    void aRolledBackChangeIsNeverSent() throws Exception {
        BlockingQueue<Sse> events = subscribe();
        first(events);
        String rolledBack = newTxnId();
        String committed = newTxnId();

        tx.executeWithoutResult(status -> {
            txns.create(rolledBack, "ravi@banka", "anu@bankb", 20_000);    // published inside the transaction...
            status.setRollbackOnly();                                      // ...which then rolls back
        });
        txns.create(committed, "ravi@banka", "anu@bankb", 20_000);         // a marker that DOES commit

        List<JsonNode> seen = new ArrayList<>();
        List<String> marker = statesOf(events, committed, s -> !s.isEmpty(), seen);
        assertEquals(List.of("INITIATED"), marker, "the committed change arrives");
        // Changes are sent in the order they were published, so the rolled-back one would have
        // arrived before (or with) the marker. Everything received so far is in "seen".
        assertFalse(seen.stream().anyMatch(change -> change.get("txnId").asText().equals(rolledBack)),
                "a change that was rolled back reached the browser");
        assertTrue(txns.history(committed).size() == 1);
    }

    @Test
    void theSnapshotHasTheNewestPaymentsWithTheirPath() throws Exception {
        String txnId = newTxnId();
        var txn = txns.create(txnId, "ravi@banka", "anu@bankb", 20_000);
        txns.transition(txnId, txn.version(), TxnState.FAILED, "test");

        Sse snapshot = first(subscribe());

        assertEquals("snapshot", snapshot.name());
        JsonNode mine = null;
        for (JsonNode t : snapshot.data()) {
            if (t.get("txnId").asText().equals(txnId)) {
                mine = t;
            }
        }
        assertNotNull(mine, "the newest payment is in the snapshot");
        assertEquals("FAILED", mine.get("state").asText());
        assertEquals(2, mine.get("path").size());
        assertEquals("INITIATED", mine.get("path").get(0).get("state").asText());
        assertEquals(20_000, mine.get("amount").asLong());
    }

    @Test
    void aClosedBrowserIsForgottenWithoutLeakingAnything() throws Exception {
        int before = stream.subscriberCount();
        BlockingQueue<Sse> events = subscribe();
        first(events);
        assertEquals(before + 1, stream.subscriberCount());

        open.remove(0).close();                        // the tab is closed

        // The next writes to it fail, and that failure removes it.
        Instant deadline = Instant.now().plusSeconds(10);
        while (stream.subscriberCount() > before && Instant.now().isBefore(deadline)) {
            stream.heartbeat();
            Thread.sleep(Duration.ofMillis(100));
        }
        assertEquals(before, stream.subscriberCount());
    }
}

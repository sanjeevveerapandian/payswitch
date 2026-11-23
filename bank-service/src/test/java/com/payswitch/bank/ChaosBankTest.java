package com.payswitch.bank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.payswitch.common.bank.BalanceResponse;
import com.payswitch.common.bank.BankResponse;
import com.payswitch.common.bank.BankResult;
import com.payswitch.common.bank.DeclineReason;
import com.payswitch.common.bank.OpType;
import com.payswitch.common.bank.StatusResponse;
import com.payswitch.common.bank.TxnStatus;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;

/**
 * Chaos on a real bank over real HTTP, with the "chaos" profile on. Each test switches one mode
 * on, then checks both what the CALLER saw and what really happened in the database.
 */
@ActiveProfiles("chaos")
@TestPropertySource(properties = "bank.chaos.drop-response-sleep=2s")
class ChaosBankTest extends BankIntegrationTest {

    @LocalServerPort
    private int port;

    /** Gives up after 500 ms, like a switch with a short read timeout. */
    private RestClient caller;

    @BeforeEach
    void connect() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofMillis(500));
        caller = RestClient.builder().baseUrl("http://localhost:" + port).requestFactory(factory).build();
    }

    /** Runs before the base class's ledger audit (JUnit runs subclass @AfterEach first). */
    @AfterEach
    void chaosOff() {
        setChaos(Map.of());
    }

    @Test
    void allRatesStartAtZero() {
        Map<?, ?> settings = caller.get().uri("/admin/chaos").retrieve().body(Map.class);

        assertEquals(0.0, settings.get("declineRate"));
        assertEquals(0.0, settings.get("errorRate"));
        assertEquals(0, settings.get("delayMs"));
        assertEquals(0.0, settings.get("dropResponseRate"));
    }

    @Test
    void declineAnswersDeclinedAndChangesNothing() {
        String ravi = newCustomer(500_000);
        String txn = newTxnId();
        setChaos(Map.of("declineRate", 1.0));

        BankResponse r = debit(txn, ravi, 20_000);

        assertEquals(BankResult.DECLINED, r.result());
        assertEquals(DeclineReason.SIMULATED_DECLINE, r.reason());
        assertEquals(500_000, balance(ravi));
        assertEquals(TxnStatus.NOT_FOUND, status(txn, OpType.DEBIT));   // not even recorded

        // Proof that nothing was saved: with chaos off, the SAME txnId goes through normally.
        setChaos(Map.of());
        assertEquals(BankResult.SUCCESS, debit(txn, ravi, 20_000).result());
        assertEquals(480_000, balance(ravi));
    }

    @Test
    void errorAnswers500AndChangesNothing() {
        String ravi = newCustomer(500_000);
        String txn = newTxnId();
        setChaos(Map.of("errorRate", 1.0));

        HttpServerErrorException e = assertThrows(HttpServerErrorException.class, () -> debit(txn, ravi, 20_000));

        assertEquals(500, e.getStatusCode().value());
        assertEquals(500_000, balance(ravi));
        assertEquals(TxnStatus.NOT_FOUND, status(txn, OpType.DEBIT));
    }

    @Test
    void delaySlowsTheAnswerButTheResultIsNormal() {
        String ravi = newCustomer(500_000);
        setChaos(Map.of("delayMs", 300));

        long start = System.nanoTime();
        BankResponse r = debit(newTxnId(), ravi, 20_000);
        long tookMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(BankResult.SUCCESS, r.result());
        assertTrue(tookMs >= 300, "took " + tookMs + " ms");
        assertEquals(480_000, balance(ravi));
    }

    /**
     * The caller gives up after 500 ms. The bank is still asleep (2 s) on that request. Yet the
     * money has ALREADY moved: proof the commit happened before the sleep, not after.
     */
    @Test
    void dropResponseCommitsFirstThenGoesSilent() {
        String ravi = newCustomer(500_000);
        String txn = newTxnId();
        setChaos(Map.of("dropResponseRate", 1.0));

        RestClientException e = assertThrows(RestClientException.class, () -> debit(txn, ravi, 20_000));
        assertTrue(causedByTimeout(e), "expected a read timeout, got " + e);

        // Checked right away, while the bank thread is still sleeping:
        assertEquals(480_000, balance(ravi));
        assertEquals(TxnStatus.SUCCESS, status(txn, OpType.DEBIT));
    }

    @Test
    void chaosNeverTouchesStatusOrBalance() {
        String ravi = newCustomer(500_000);
        setChaos(Map.of("errorRate", 1.0, "delayMs", 1000));

        // Both would fail (500, or time out after 500 ms) if chaos applied to them.
        assertEquals(500_000, balance(ravi));
        assertEquals(TxnStatus.NOT_FOUND, status(newTxnId(), OpType.DEBIT));
    }

    @Test
    void sameSeedGivesTheSameSequenceOfAnswers() {
        String ravi = newCustomer(1_000_000);
        Map<String, Object> seeded = Map.of("declineRate", 0.3, "errorRate", 0.3, "seed", 42);

        setChaos(seeded);
        List<String> first = twentyDebits(ravi);
        setChaos(seeded);
        List<String> second = twentyDebits(ravi);

        assertEquals(first, second);
        assertTrue(first.contains("SUCCESS") && first.contains("DECLINED") && first.contains("HTTP 500"), first.toString());
    }

    @Test
    void invalidSettingsAreA400() {
        for (Map<String, Object> bad : List.<Map<String, Object>>of(
                Map.of("declineRate", 1.5),
                Map.of("declineRate", 0.6, "errorRate", 0.6),
                Map.of("delayMs", -1))) {
            HttpClientErrorException e = assertThrows(HttpClientErrorException.class,
                    () -> caller.post().uri("/admin/chaos").body(bad).retrieve().toBodilessEntity());
            assertEquals(400, e.getStatusCode().value());
        }
    }

    // ---------------------------------------------------------------------------------------

    /** The timeout can arrive wrapped in one or more exceptions, so look through the whole chain. */
    private static boolean causedByTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    // ---- Phase 12: chaos once -------------------------------------------------------------------

    @Test
    void chaosOnceFailsTheNextMatchingRequestOnlyThenResetsItself() {
        String ravi = newCustomer(500_000);
        caller.post().uri("/admin/chaos/once").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("op", "DEBIT", "mode", "error")).retrieve().toBodilessEntity();
        assertEquals(Map.of("DEBIT", "ERROR"), caller.get().uri("/admin/chaos/once").retrieve().body(Map.class));

        String first = newTxnId();
        assertThrows(HttpServerErrorException.class, () -> debit(first, ravi, 10_000));   // the armed one
        BankResponse second = debit(newTxnId(), ravi, 10_000);                            // back to normal
        BankResponse third = debit(newTxnId(), ravi, 10_000);

        assertEquals(BankResult.SUCCESS, second.result());
        assertEquals(BankResult.SUCCESS, third.result());
        assertEquals(TxnStatus.NOT_FOUND, status(first, OpType.DEBIT));   // the failed one moved nothing
        assertEquals(480_000, balance(ravi));
        assertEquals(Map.of(), caller.get().uri("/admin/chaos/once").retrieve().body(Map.class));
    }

    @Test
    void chaosOnceOnlyTouchesTheOperationItWasArmedFor() {
        String ravi = newCustomer(500_000);
        caller.post().uri("/admin/chaos/once").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("op", "CREDIT", "mode", "decline")).retrieve().toBodilessEntity();

        assertEquals(BankResult.SUCCESS, debit(newTxnId(), ravi, 10_000).result());   // a DEBIT: untouched
        assertEquals(Map.of("CREDIT", "DECLINE"), caller.get().uri("/admin/chaos/once").retrieve().body(Map.class));
    }

    @Test
    void resettingChaosAlsoForgetsArmedOnes() {
        caller.post().uri("/admin/chaos/once").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("op", "REVERSE", "mode", "drop_response")).retrieve().toBodilessEntity();
        setChaos(Map.of());
        assertEquals(Map.of(), caller.get().uri("/admin/chaos/once").retrieve().body(Map.class));
    }

    @Test
    void aBadOnceRequestIs400() {
        HttpClientErrorException e = assertThrows(HttpClientErrorException.class, () ->
                caller.post().uri("/admin/chaos/once").contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("op", "STATUS_CHECK", "mode", "decline")).retrieve().toBodilessEntity());
        assertEquals(400, e.getStatusCode().value());
    }

    private void setChaos(Map<String, Object> settings) {
        caller.post().uri("/admin/chaos").body(settings).retrieve().toBodilessEntity();
    }

    private BankResponse debit(String txnId, String vpa, long amount) {
        return caller.post().uri("/debit")
                .body(Map.of("txnId", txnId, "vpa", vpa, "amount", amount))
                .retrieve().body(BankResponse.class);
    }

    private TxnStatus status(String txnId, OpType op) {
        return caller.get().uri("/status/{t}?op={op}", txnId, op).retrieve().body(StatusResponse.class).status();
    }

    @Override
    protected long balance(String vpa) {
        return caller.get().uri("/accounts/{vpa}/balance", vpa).retrieve().body(BalanceResponse.class).balance();
    }

    private List<String> twentyDebits(String vpa) {
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            try {
                seen.add(debit(newTxnId(), vpa, 100).result().name());
            } catch (HttpServerErrorException e) {
                seen.add("HTTP " + e.getStatusCode().value());
            }
        }
        return seen;
    }
}

package com.payswitch.switchservice.bank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.payswitch.common.bank.DebitRequest;
import com.payswitch.common.bank.DeclineReason;
import com.payswitch.switchservice.metrics.SwitchMetrics;
import com.payswitch.switchservice.routing.BankRoute;
import com.payswitch.switchservice.routing.SwitchProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Every way a bank call can end, and the ONE outcome each must map to. A real HTTP server on
 * localhost plays the bank, so timeouts, refused and reset connections are real, not mocked.
 */
class BankClientTest {

    private static final String TXN = "TXN-1";
    private static final DebitRequest DEBIT = new DebitRequest(TXN, "ravi@banka", 20_000);

    private HttpServer server;
    private volatile Handler handler;
    private BankClient client;
    private final SwitchMetrics metrics = SwitchMetrics.inMemory();

    interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    @BeforeEach
    void startFakeBank() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> handler.handle(exchange));
        server.start();
        // Short read timeout so the timeout test is quick.
        client = new BankClient(RestClient.builder(), new SwitchProperties(
                new SwitchProperties.Http(Duration.ofSeconds(1), Duration.ofMillis(300)),
                Map.of("banka", bank(server.getAddress().getPort()))), metrics);
    }

    @AfterEach
    void stopFakeBank() {
        server.stop(0);
    }

    // ---- 2xx with a clear answer --------------------------------------------------------

    @Test
    void ok200WithSuccessIsSuccess() {
        answer(200, body("SUCCESS", null));

        BankCallResult r = client.debit(bank(), DEBIT);

        assertEquals(BankCallOutcome.SUCCESS, r.outcome());
        assertNull(r.declineReason());
    }

    @Test
    void ok200WithDeclinedIsDeclinedAndKeepsTheReason() {
        answer(200, body("DECLINED", "INSUFFICIENT_FUNDS"));

        BankCallResult r = client.debit(bank(), DEBIT);

        assertEquals(BankCallOutcome.DECLINED, r.outcome());
        assertEquals(DeclineReason.INSUFFICIENT_FUNDS, r.declineReason());
        assertEquals("INSUFFICIENT_FUNDS", r.detail());
    }

    // ---- no clear answer: everything below is UNKNOWN -----------------------------------

    @Test
    void readTimeoutIsUnknown() {
        handler = exchange -> {
            sleep(1_000);                       // longer than the 300 ms read timeout
            reply(exchange, 200, body("SUCCESS", null));
        };

        assertUnknown(client.debit(bank(), DEBIT), "timeout");
    }

    @Test
    void connectionRefusedIsUnknown() throws IOException {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            closedPort = s.getLocalPort();      // nothing listens here once closed
        }

        assertUnknown(client.debit(bank(closedPort), DEBIT), "connection refused");
    }

    /** The bank accepts the connection, then slams it shut without answering. */
    @Test
    void connectionDroppedWithoutAnAnswerIsUnknown() throws Exception {
        try (ServerSocket rude = new ServerSocket(0)) {
            Thread t = new Thread(() -> {
                try (Socket s = rude.accept()) {
                    s.getInputStream().read();      // wait until the request starts arriving
                    s.setSoLinger(true, 0);         // close with a TCP reset, not a polite goodbye
                } catch (IOException ignored) {
                    // the test only cares about the client side
                }
            });
            t.start();

            BankCallResult r = client.debit(bank(rude.getLocalPort()), DEBIT);

            t.join(2_000);
            assertEquals(BankCallOutcome.UNKNOWN, r.outcome());
            assertTrue(Set.of("connection reset", "connection closed", "connection error").contains(r.detail()),
                    r.detail());
        }
    }

    @ParameterizedTest(name = "HTTP {0} -> UNKNOWN")
    @ValueSource(ints = {500, 502, 503, 504})
    void serverErrorIsUnknown(int status) {
        answer(status, "{\"title\":\"Internal Server Error\"}");

        assertUnknown(client.debit(bank(), DEBIT), "HTTP " + status);
    }

    /**
     * A 4xx means the bank refused to process our request, so most likely nothing moved. But
     * only an explicit DECLINED is allowed to count as "no". Anything else stays UNKNOWN; the
     * recovery job (Phase 6) asks the bank's /status instead of guessing.
     */
    @ParameterizedTest(name = "HTTP {0} -> UNKNOWN")
    @ValueSource(ints = {400, 404, 409})
    void clientErrorIsAlsoUnknown(int status) {
        answer(status, "{}");

        assertUnknown(client.debit(bank(), DEBIT), "HTTP " + status);
    }

    @Test
    void ok200WithGarbageIsUnknown() {
        answer(200, "<html>Gateway says hello</html>");

        assertUnknown(client.debit(bank(), DEBIT), "unreadable response");
    }

    @Test
    void ok200WithAnUnknownResultValueIsUnknown() {
        answer(200, "{\"txnId\":\"TXN-1\",\"op\":\"DEBIT\",\"result\":\"MAYBE\"}");

        assertUnknown(client.debit(bank(), DEBIT), "unreadable response");
    }

    @Test
    void ok200WithNoResultFieldIsUnknown() {
        answer(200, "{\"txnId\":\"TXN-1\",\"op\":\"DEBIT\"}");

        assertUnknown(client.debit(bank(), DEBIT), "unreadable response");
    }

    @Test
    void ok200WithAnEmptyBodyIsUnknown() {
        answer(200, "");

        assertEquals(BankCallOutcome.UNKNOWN, client.debit(bank(), DEBIT).outcome());
    }

    @Test
    void ok200AboutADifferentTxnIsUnknown() {
        answer(200, "{\"txnId\":\"SOMEONE-ELSE\",\"op\":\"DEBIT\",\"result\":\"SUCCESS\"}");

        assertUnknown(client.debit(bank(), DEBIT), "mismatched response");
    }

    @Test
    void ok200AboutADifferentOperationIsUnknown() {
        answer(200, "{\"txnId\":\"TXN-1\",\"op\":\"CREDIT\",\"result\":\"SUCCESS\"}");

        assertUnknown(client.debit(bank(), DEBIT), "mismatched response");
    }

    // ---- the cause-chain reader, on failures that are hard to produce for real ----------

    @Test
    void describeReadsTheWholeCauseChain() {
        assertEquals("timeout", BankClient.describe(wrapped(new SocketTimeoutException("Read timed out"))));
        assertEquals("connect timeout", BankClient.describe(wrapped(new SocketTimeoutException("Connect timed out"))));
        assertEquals("connection refused", BankClient.describe(wrapped(new ConnectException("Connection refused"))));
        assertEquals("connection reset", BankClient.describe(wrapped(new SocketException("Connection reset"))));
        assertEquals("unknown host", BankClient.describe(wrapped(new UnknownHostException("bank-z"))));
        assertEquals("connection closed", BankClient.describe(wrapped(new EOFException())));
        assertEquals("unreadable response", BankClient.describe(new RestClientException("cannot parse")));
    }

    // ---------------------------------------------------------------------------------------

    private static RestClientException wrapped(IOException cause) {
        // Two layers deep, the way RestClient really reports them.
        return new RestClientException("Error while extracting response",
                new ResourceAccessException("I/O error", cause));
    }

    private static void assertUnknown(BankCallResult r, String expectedDetail) {
        assertEquals(BankCallOutcome.UNKNOWN, r.outcome());
        assertEquals(expectedDetail, r.detail());
        assertNull(r.declineReason());
    }

    private BankRoute bank() {
        return bank(server.getAddress().getPort());
    }

    private static BankRoute bank(int port) {
        return new BankRoute("BANKA", URI.create("http://localhost:" + port));
    }

    private void answer(int status, String body) {
        handler = exchange -> reply(exchange, status, body);
    }

    private static String body(String result, String reason) {
        return "{\"txnId\":\"" + TXN + "\",\"op\":\"DEBIT\",\"result\":\"" + result + "\",\"reason\":"
                + (reason == null ? "null" : "\"" + reason + "\"") + ",\"message\":\"x\"}";
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

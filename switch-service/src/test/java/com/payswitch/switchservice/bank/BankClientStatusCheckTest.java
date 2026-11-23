package com.payswitch.switchservice.bank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.payswitch.common.bank.DeclineReason;
import com.payswitch.common.bank.OpType;
import com.payswitch.common.bank.ReverseRequest;
import com.payswitch.switchservice.metrics.SwitchMetrics;
import com.payswitch.switchservice.routing.BankRoute;
import com.payswitch.switchservice.routing.SwitchProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** How a /status-check answer (and a NO_DEBIT reversal) maps to SUCCESS / DECLINED / UNKNOWN. */
class BankClientStatusCheckTest {

    private HttpServer server;
    private final AtomicReference<String> answer = new AtomicReference<>();
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private BankClient client;
    private final SwitchMetrics metrics = SwitchMetrics.inMemory();
    private BankRoute bank;

    @BeforeEach
    void startFakeBank() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = answer.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        bank = new BankRoute("BANKA", URI.create("http://localhost:" + server.getAddress().getPort()));
        client = new BankClient(RestClient.builder(), new SwitchProperties(
                new SwitchProperties.Http(Duration.ofSeconds(1), Duration.ofSeconds(1)), Map.of("banka", bank)), metrics);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void alwaysAsksTheBankToFence() {
        answer.set(check("FENCED", null));

        client.statusCheckAndFence(bank, "T1", OpType.DEBIT);

        assertTrue(lastRequestBody.get().contains("\"fence\":true"), lastRequestBody.get());
    }

    @Test
    void foundAndSucceededIsSuccess() {
        answer.set(check("FOUND", saved("SUCCESS", null)));

        assertEquals(BankCallOutcome.SUCCESS, client.statusCheckAndFence(bank, "T1", OpType.DEBIT).outcome());
    }

    @Test
    void foundAndDeclinedIsDeclinedWithTheSavedReason() {
        answer.set(check("FOUND", saved("DECLINED", "INSUFFICIENT_FUNDS")));

        BankCallResult r = client.statusCheckAndFence(bank, "T1", OpType.DEBIT);

        assertEquals(BankCallOutcome.DECLINED, r.outcome());
        assertEquals(DeclineReason.INSUFFICIENT_FUNDS, r.declineReason());
    }

    @Test
    void fencedIsDeclinedFenced() {
        answer.set(check("FENCED", saved("DECLINED", "FENCED")));

        BankCallResult r = client.statusCheckAndFence(bank, "T1", OpType.DEBIT);

        assertEquals(BankCallOutcome.DECLINED, r.outcome());
        assertEquals(DeclineReason.FENCED, r.declineReason());
    }

    /** NOT_FOUND without a fence is no promise: a late request could still run. Not final. */
    @Test
    void notFoundIsUnknown() {
        answer.set(check("NOT_FOUND", null));

        BankCallResult r = client.statusCheckAndFence(bank, "T1", OpType.DEBIT);

        assertEquals(BankCallOutcome.UNKNOWN, r.outcome());
        assertEquals("not found and not fenced", r.detail());
    }

    @Test
    void serverErrorIsUnknown() {
        status.set(500);
        answer.set("{}");

        assertEquals("HTTP 500", client.statusCheckAndFence(bank, "T1", OpType.DEBIT).detail());
    }

    /** A status check that cannot even reach the bank is NOT a "no": it must stay UNKNOWN. */
    @Test
    void aStatusCheckThatCannotConnectIsUnknown() throws Exception {
        int closedPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        BankRoute down = new BankRoute("BANKA", URI.create("http://localhost:" + closedPort));

        BankCallResult r = client.statusCheckAndFence(down, "T1", OpType.DEBIT);

        assertEquals(BankCallOutcome.UNKNOWN, r.outcome());
        assertEquals("connection refused", r.detail());
    }

    @Test
    void aStatusCheckWithAnEmptyBodyIsUnknown() {
        answer.set("");

        assertEquals(BankCallOutcome.UNKNOWN, client.statusCheckAndFence(bank, "T1", OpType.DEBIT).outcome());
    }

    @Test
    void anAnswerAboutAnotherOperationIsUnknown() {
        answer.set(check("FOUND", saved("SUCCESS", null)));        // about DEBIT

        assertEquals("mismatched response", client.statusCheckAndFence(bank, "T1", OpType.CREDIT).detail());
    }

    @Test
    void reverseAnsweringNoDebitIsASuccess() {
        answer.set("{\"txnId\":\"T1\",\"op\":\"REVERSE\",\"result\":\"NO_DEBIT\",\"reason\":null,\"message\":\"x\"}");

        BankCallResult r = client.reverse(bank, new ReverseRequest("T1"));

        assertEquals(BankCallOutcome.SUCCESS, r.outcome());
        assertTrue(r.isNoDebit());
    }

    @Test
    void noDebitOnADebitMakesNoSenseSoItIsUnknown() {
        answer.set(check("FOUND", "{\"txnId\":\"T1\",\"op\":\"DEBIT\",\"result\":\"NO_DEBIT\",\"reason\":null,\"message\":\"x\"}"));

        assertEquals(BankCallOutcome.UNKNOWN, client.statusCheckAndFence(bank, "T1", OpType.DEBIT).outcome());
    }

    private static String check(String result, String savedJson) {
        return "{\"txnId\":\"T1\",\"op\":\"DEBIT\",\"result\":\"" + result + "\",\"saved\":" + savedJson + "}";
    }

    private static String saved(String result, String reason) {
        return "{\"txnId\":\"T1\",\"op\":\"DEBIT\",\"result\":\"" + result + "\",\"reason\":"
                + (reason == null ? "null" : "\"" + reason + "\"") + ",\"message\":\"x\"}";
    }
}

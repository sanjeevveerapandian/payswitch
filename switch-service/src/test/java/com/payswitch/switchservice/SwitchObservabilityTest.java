package com.payswitch.switchservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Phase 11, switch side. The REAL BankClient bean (built from Spring Boot's RestClient.Builder)
 * calls a fake bank over real HTTP, so this checks the wiring, not just the pieces:
 * <ul>
 *   <li>the X-Txn-Id header reaches the bank on every call of the payment;</li>
 *   <li>log lines written during the payment carry txnId and state as JSON fields;</li>
 *   <li>/actuator/prometheus shows the counters and histograms with the right labels, and never a
 *       txnId or VPA.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "switch.recon.enabled=false",
        "switch.settlement.enabled=false",
        "switch.resolution.run-on-startup=false",
        "switch.resolution.interval=1h",
})
@Import(TestcontainersConfig.class)
@AutoConfigureMockMvc
@AutoConfigureObservability(tracing = false)
@ExtendWith(OutputCaptureExtension.class)
class SwitchObservabilityTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpServer FAKE_BANK = startFakeBank();
    /** "DEBIT:TXN-..." -> the X-Txn-Id header that request carried. */
    private static final Map<String, String> HEADERS_SEEN = new ConcurrentHashMap<>();
    /** txnIds whose debit gets an HTTP 500 (the switch must then resolve with a status check). */
    private static final Map<String, Boolean> FAIL_DEBIT = new ConcurrentHashMap<>();

    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void banksAreTheFake(DynamicPropertyRegistry registry) {
        String url = "http://localhost:" + FAKE_BANK.getAddress().getPort();
        registry.add("switch.banks.banka.url", () -> url);
        registry.add("switch.banks.bankb.url", () -> url);
        registry.add("switch.banks.bankc.url", () -> url);
    }

    @AfterAll
    static void stopFakeBank() {
        FAKE_BANK.stop(0);
    }

    // ---- the fake bank ---------------------------------------------------------------------

    private static HttpServer startFakeBank() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", SwitchObservabilityTest::answer);
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void answer(HttpExchange ex) throws IOException {
        JsonNode body = JSON.readTree(ex.getRequestBody());
        String txnId = body.get("txnId").asText();
        String path = ex.getRequestURI().getPath();
        String op = switch (path) {
            case "/debit" -> "DEBIT";
            case "/credit" -> "CREDIT";
            case "/reverse" -> "REVERSE";
            default -> "STATUS_CHECK";
        };
        HEADERS_SEEN.put(op + ":" + txnId, String.valueOf(ex.getRequestHeaders().getFirst("X-Txn-Id")));
        String reply;
        int status = 200;
        if (op.equals("STATUS_CHECK")) {
            // "No record of that debit, and now it is fenced": the debit never happened.
            String checked = body.get("op").asText();
            reply = """
                    {"txnId":"%s","op":"%s","result":"FENCED","saved":{"txnId":"%s","op":"%s","result":"DECLINED","reason":"FENCED","message":"fenced"}}"""
                    .formatted(txnId, checked, txnId, checked);
        } else if (op.equals("DEBIT") && FAIL_DEBIT.containsKey(txnId)) {
            status = 500;
            reply = "{}";
        } else {
            reply = """
                    {"txnId":"%s","op":"%s","result":"SUCCESS","reason":null,"message":"OK"}""".formatted(txnId, op);
        }
        byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ---- helpers ----------------------------------------------------------------------------

    private void pay(String txnId, String expectedState) throws Exception {
        mvc.perform(post("/pay").contentType(MediaType.APPLICATION_JSON).content("""
                        {"txnId":"%s","payerVpa":"ravi@banka","payeeVpa":"anu@bankb","amount":20000}""".formatted(txnId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value(expectedState));
    }

    private String scrape() throws Exception {
        return mvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getContentAsString();
    }

    /** Value of the first series of {@code name} whose labels contain all of {@code labels}; 0 if none. */
    private static double value(String scrape, String name, String... labels) {
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(name) + "\\{([^}]*)} (\\S+)$").matcher(scrape);
        while (m.find()) {
            String found = m.group(1);
            if (List.of(labels).stream().allMatch(found::contains)) {
                return Double.parseDouble(m.group(2));
            }
        }
        return 0;
    }

    private static String newTxnId() {
        return "TXN-" + UUID.randomUUID();
    }

    // ---- tests -------------------------------------------------------------------------------

    @Test
    void theTxnIdHeaderReachesTheBankOnEveryCallOfThePayment() throws Exception {
        String txn = newTxnId();

        pay(txn, "SUCCESS");

        assertEquals(txn, HEADERS_SEEN.get("DEBIT:" + txn));
        assertEquals(txn, HEADERS_SEEN.get("CREDIT:" + txn));
    }

    @Test
    void logLinesDuringAPaymentCarryItsTxnIdAndState(CapturedOutput output) throws Exception {
        String txn = newTxnId();

        pay(txn, "SUCCESS");

        List<String> lines = output.getOut().lines()
                .filter(l -> l.contains("\"txnId\":\"" + txn + "\"")).toList();
        assertTrue(lines.size() >= 5, "created + 4 state changes, all with the txnId field: " + lines);
        assertTrue(lines.stream().allMatch(l -> l.startsWith("{") && l.contains("\"service\":\"switch-service\"")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"state\":\"SUCCESS\"")
                        && l.contains("CREDIT_SENT -> SUCCESS")),
                "the line about reaching SUCCESS carries state SUCCESS: " + lines);
        assertFalse(lines.stream().anyMatch(l -> l.contains("ravi@banka")), "no VPA in the switch's logs");
    }

    @Test
    void prometheusShowsCountersAndHistogramsWithFixedLabelsOnly() throws Exception {
        String before = scrape();
        String txn = newTxnId();

        pay(txn, "SUCCESS");

        String after = scrape();
        assertEquals(value(before, "payswitch_payments_total", "final_state=\"SUCCESS\"") + 1,
                value(after, "payswitch_payments_total", "final_state=\"SUCCESS\""));
        assertTrue(value(after, "payswitch_bank_call_seconds_count", "bank=\"BANKA\"", "op=\"DEBIT\"",
                "outcome=\"SUCCESS\"") >= 1);
        assertTrue(after.contains("payswitch_bank_call_seconds_bucket{"), "a histogram, so p99 can be computed");
        assertTrue(after.contains("payswitch_txns_in_state{"), "the per-state gauge exists");
        assertTrue(after.contains("state=\"DEEMED_APPROVED\""), "every unfinished state shows, even at 0");
        assertFalse(after.contains(txn), "a txnId as a label would mean one time series per payment");
        assertFalse(after.contains("ravi@banka"), "a VPA must never be a label");
    }

    @Test
    void anUnknownDebitShowsUpAsAStatusCheckAndAResolverFix() throws Exception {
        String before = scrape();
        String txn = newTxnId();
        FAIL_DEBIT.put(txn, true);

        pay(txn, "FAILED");                            // HTTP 500 -> status check -> FENCED -> FAILED

        String after = scrape();
        assertEquals(txn, HEADERS_SEEN.get("STATUS_CHECK:" + txn), "the status check carries the header too");
        assertEquals(value(before, "payswitch_bank_call_seconds_count", "bank=\"BANKA\"", "op=\"DEBIT\"",
                        "outcome=\"UNKNOWN\"") + 1,
                value(after, "payswitch_bank_call_seconds_count", "bank=\"BANKA\"", "op=\"DEBIT\"",
                        "outcome=\"UNKNOWN\""));
        assertEquals(value(before, "payswitch_status_checks_total", "bank=\"BANKA\"", "result=\"FENCED\"") + 1,
                value(after, "payswitch_status_checks_total", "bank=\"BANKA\"", "result=\"FENCED\""));
        assertEquals(value(before, "payswitch_resolver_fixed_total", "from_state=\"DEBIT_SENT\"") + 1,
                value(after, "payswitch_resolver_fixed_total", "from_state=\"DEBIT_SENT\""));
        assertEquals(value(before, "payswitch_payments_total", "final_state=\"FAILED\"") + 1,
                value(after, "payswitch_payments_total", "final_state=\"FAILED\""));
    }
}

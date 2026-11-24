package com.payswitch.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.payswitch.common.pay.PayState;
import com.payswitch.common.pay.RespPay;
import com.payswitch.common.pay.TxnDetails;
import com.payswitch.common.pay.TxnEventView;
import com.payswitch.switchservice.demo.DemoReseedService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Phase 12, against real banks: the demo's scenarios do what their buttons say, and the money
 * summary's TOTAL does not move, even when a payment is debited and then reversed.
 */
class DemoEndToEndTest {

    private static final PaymentSystem system = PaymentSystem.shared();
    private static final RestClient http = RestClient.create();

    private long totalBefore;

    @BeforeEach
    void start() {
        system.chaosOff();
        totalBefore = summary().get("total").asLong();
    }

    /** Whatever a scenario does, no money is created or destroyed. */
    @AfterEach
    void totalNeverChanges() {
        system.chaosOff();
        assertEquals(totalBefore, summary().get("total").asLong(), "TOTAL changed");
        system.auditAllBanks();
    }

    private JsonNode summary() {
        return http.get().uri(system.switchUrl() + "/admin/money-summary").retrieve().body(JsonNode.class);
    }

    private JsonNode scenario(String name) {
        return http.post().uri(system.switchUrl() + "/admin/scenario/" + name).retrieve().body(JsonNode.class);
    }

    private List<String> reasons(String txnId) {
        TxnDetails d = http.get().uri(system.switchUrl() + "/txns/" + txnId).retrieve().body(TxnDetails.class);
        return d.events().stream().map(TxnEventView::reason).toList();
    }

    private String state(String txnId) {
        return http.get().uri(system.switchUrl() + "/txns/" + txnId).retrieve().body(TxnDetails.class).state();
    }

    // ---- money summary ------------------------------------------------------------------------

    @Test
    void theSummaryAddsUpTheBanksOwnFigures() {
        JsonNode s = summary();
        long customers = 0;
        long inTransit = 0;
        for (String bank : List.of(system.bankAUrl(), system.bankBUrl(), system.bankCUrl())) {
            JsonNode b = http.get().uri(bank + "/admin/summary").retrieve().body(JsonNode.class);
            customers += b.get("customerMoney").asLong();
            inTransit += b.get("switchSettlement").asLong() - b.get("centralReserve").asLong();
        }
        assertEquals(customers, s.get("customerMoney").asLong());
        assertEquals(inTransit, s.get("inTransit").asLong());
        assertEquals(customers + inTransit, s.get("total").asLong());
        assertEquals(3, s.get("banks").size());
    }

    @Test
    void totalIsUnchangedAfterAPaymentIsDebitedAndThenReversed() {
        long customersBefore = summary().get("customerMoney").asLong();
        // Bank B fails the NEXT credit only: HTTP 500, nothing processed. The status check then finds no
        // credit and fences it, so the switch refunds Ravi.
        http.post().uri(system.bankBUrl() + "/admin/chaos/once").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("op", "CREDIT", "mode", "error")).retrieve().toBodilessEntity();

        RespPay r = http.post().uri(system.pspUrl() + "/pay").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("payerVpa", "ravi@banka", "payeeVpa", "anu@bankb", "amount", "200.00"))
                .retrieve().body(RespPay.class);

        assertEquals(PayState.REVERSED, r.state());
        JsonNode after = summary();
        assertEquals(customersBefore, after.get("customerMoney").asLong(), "Ravi got his Rs 200 back");
        assertEquals(totalBefore, after.get("total").asLong());
        assertTrue(after.get("txnsByState").get("REVERSED").asLong() >= 1);
    }

    // ---- scenarios ----------------------------------------------------------------------------

    @Test
    void happyPathSucceeds() {
        JsonNode r = scenario("happy");
        assertEquals("SUCCESS", r.get("state").asText());
    }

    @Test
    void insufficientFundsFailsAndMovesNothing() {
        JsonNode r = scenario("insufficient-funds");
        assertEquals("FAILED", r.get("state").asText());
        assertTrue(reasons(r.get("txnId").asText()).contains("DEBIT_DECLINED:INSUFFICIENT_FUNDS"));
    }

    @Test
    void aLostDebitReplyIsFoundByAStatusCheck() {
        JsonNode r = scenario("debit-reply-lost");
        String txnId = r.get("txnId").asText();
        assertEquals("SUCCESS", r.get("state").asText());
        assertTrue(reasons(txnId).contains("DEBIT_CONFIRMED_BY_STATUS"), reasons(txnId).toString());
        assertEquals(Map.of(), http.get().uri(system.bankAUrl() + "/admin/chaos/once").retrieve().body(Map.class),
                "the scenario left no one-shot rule behind");
    }

    @Test
    void anUnknownCreditIsDeemedApprovedAndReconFinishesIt() throws InterruptedException {
        Instant from = Instant.now().minusSeconds(1);
        JsonNode r = scenario("credit-unknown-deemed");
        String txnId = r.get("txnId").asText();
        assertEquals("DEEMED_APPROVED", r.get("state").asText());
        // The scenario put Bank B's status checks back to normal afterwards.
        assertEquals(0.0, http.get().uri(system.bankBUrl() + "/admin/chaos").retrieve().body(JsonNode.class)
                .get("statusErrorRate").asDouble());

        Thread.sleep(PaymentSystem.RECON_CUTOFF.toMillis() + 500);       // old enough for recon
        http.post().uri(system.switchUrl() + "/admin/recon/run?from={f}&to={t}", from, Instant.now())
                .retrieve().toBodilessEntity();

        assertEquals("SUCCESS", state(txnId));
        assertTrue(reasons(txnId).contains("RECON_CREDIT_FOUND"), reasons(txnId).toString());
    }

    // ---- Phase 13: reseed -------------------------------------------------------------------------

    /** The reseed's starting balances are the ones the banks were really seeded with. */
    @Test
    void reseedTargetsMatchTheBanksSeedFiles() throws IOException {
        Map<String, Long> seeded = new HashMap<>();
        for (String bank : List.of("BANKA", "BANKB", "BANKC")) {
            String sql;
            try (InputStream in = getClass().getResourceAsStream("/db/bank/seed/" + bank + "/V100__seed_customers.sql")) {
                sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            Map<String, String> vpaById = new HashMap<>();
            Matcher account = Pattern.compile("\\('(ACC-\\w+)',\\s*'([^']+@[^']+)'").matcher(sql);
            while (account.find()) {
                vpaById.put(account.group(1), account.group(2));
            }
            Matcher line = Pattern.compile("'(ACC-\\w+)',\\s*-(\\d+)\\)").matcher(sql);
            while (line.find()) {
                if (vpaById.containsKey(line.group(1))) {
                    seeded.put(vpaById.get(line.group(1)), Long.parseLong(line.group(2)));
                }
            }
        }
        assertEquals(seeded, DemoReseedService.STARTING_BALANCES);
    }

    /** Ravi pays Anu; the reseed pays it back with ordinary payments. TOTAL is checked after every test. */
    @Test
    void reseedMovesMoneyBackWithOrdinaryPaymentsAndCreatesNone() {
        http.post().uri(system.pspUrl() + "/pay").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("payerVpa", "ravi@banka", "payeeVpa", "anu@bankb", "amount", "100.00"))
                .retrieve().toBodilessEntity();
        long customersBefore = summary().get("customerMoney").asLong();

        JsonNode r = http.post().uri(system.switchUrl() + "/admin/demo/reseed").retrieve().body(JsonNode.class);

        long moved = 0;
        for (JsonNode p : r.get("payments")) {
            assertEquals("SUCCESS", p.get("state").asText(), p.toString());
            assertEquals("SUCCESS", state(p.get("txnId").asText()), "a real payment the switch knows");
            moved += p.get("amount").asLong();
        }
        assertTrue(moved >= 100_00, "at least Ravi's Rs 100 went back: " + r);
        assertEquals(customersBefore, summary().get("customerMoney").asLong(), "customers' money only moved");
        boolean above = false;
        boolean below = false;
        for (Map.Entry<String, Long> start : DemoReseedService.STARTING_BALANCES.entrySet()) {
            long now = r.get("after").get(start.getKey()).asLong();
            above |= now > start.getValue();
            below |= now < start.getValue();
        }
        assertTrue(!(above && below), "as close to the start as the money allows: " + r.get("after"));
    }

    @Test
    void anUnknownScenarioIs404() {
        int status = http.post().uri(system.switchUrl() + "/admin/scenario/nope")
                .exchange((req, res) -> res.getStatusCode().value());
        assertEquals(404, status);
    }
}

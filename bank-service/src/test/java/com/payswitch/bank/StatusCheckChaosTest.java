package com.payswitch.bank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.payswitch.common.bank.BalanceResponse;
import com.payswitch.common.bank.BankResponse;
import com.payswitch.common.bank.CheckResult;
import com.payswitch.common.bank.StatusCheckResponse;
import com.payswitch.common.bank.StatusResponse;
import com.payswitch.common.bank.TxnStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

/** The statusErrorRate chaos knob. Same settings as ChaosBankTest, so Spring reuses that context. */
@ActiveProfiles("chaos")
@TestPropertySource(properties = "bank.chaos.drop-response-sleep=2s")
class StatusCheckChaosTest extends BankIntegrationTest {

    @LocalServerPort
    private int port;

    private RestClient caller;

    @BeforeEach
    void connect() {
        caller = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @AfterEach
    void chaosOff() {
        setChaos(Map.of());
    }

    @Test
    void statusErrorRateBreaksOnlyStatusCheck() {
        String ravi = newCustomer(500_000);
        String txn = newTxnId();
        setChaos(Map.of("statusErrorRate", 1.0));

        HttpServerErrorException e = assertThrows(HttpServerErrorException.class,
                () -> statusCheck(txn, true));

        assertEquals(500, e.getStatusCode().value());
        // Nothing was looked up, so nothing was fenced: the debit still goes through normally.
        assertEquals("SUCCESS", debit(txn, ravi).result().name());
        // The read-only status and balance never fail.
        assertEquals(TxnStatus.SUCCESS,
                caller.get().uri("/status/{t}?op=DEBIT", txn).retrieve().body(StatusResponse.class).status());
        assertEquals(499_900,                                  // the helper debits 100 paise
                caller.get().uri("/accounts/{v}/balance", ravi).retrieve().body(BalanceResponse.class).balance());
        // With the knob back at 0, the check works and finds it.
        setChaos(Map.of());
        assertEquals(CheckResult.FOUND, statusCheck(txn, true).result());
    }

    /** Status checks roll their own dice, so mixing them in never changes the debit sequence. */
    @Test
    void statusChecksDoNotShiftTheSeededSequence() {
        String ravi = newCustomer(1_000_000);
        Map<String, Object> seeded = new HashMap<>(Map.of("declineRate", 0.5, "seed", 7));

        setChaos(seeded);
        List<String> plain = tenDebits(ravi, false);
        seeded.put("statusErrorRate", 0.5);
        setChaos(seeded);
        List<String> withChecksInBetween = tenDebits(ravi, true);

        assertEquals(plain, withChecksInBetween);
    }

    private List<String> tenDebits(String vpa, boolean checkBetween) {
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String txn = newTxnId();
            if (checkBetween) {
                try {
                    statusCheck(newTxnId(), false);
                } catch (HttpServerErrorException ignored) {
                    // half of these fail on purpose
                }
            }
            seen.add(debit(txn, vpa).result().name());
        }
        return seen;
    }

    private void setChaos(Map<String, Object> settings) {
        caller.post().uri("/admin/chaos").body(settings).retrieve().toBodilessEntity();
    }

    private BankResponse debit(String txnId, String vpa) {
        return caller.post().uri("/debit")
                .body(Map.of("txnId", txnId, "vpa", vpa, "amount", 100))
                .retrieve().body(BankResponse.class);
    }

    private StatusCheckResponse statusCheck(String txnId, boolean fence) {
        return caller.post().uri("/status-check")
                .body(Map.of("txnId", txnId, "op", "DEBIT", "fence", fence))
                .retrieve().body(StatusCheckResponse.class);
    }
}

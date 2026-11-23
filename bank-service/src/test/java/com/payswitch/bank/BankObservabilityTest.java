package com.payswitch.bank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.payswitch.common.bank.BankResponse;
import com.payswitch.common.bank.BankResult;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Phase 11, bank side: the X-Txn-Id header that the switch sends reaches the bank's logs, and the
 * bank's counters show up in /actuator/prometheus with the right labels.
 */
@ExtendWith(OutputCaptureExtension.class)
class BankObservabilityTest extends BankIntegrationTest {

    @Autowired
    private TestRestTemplate http;

    private BankResponse debit(String txnId, String vpa, String header) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (header != null) {
            headers.set("X-Txn-Id", header);
        }
        String body = """
                {"txnId": "%s", "vpa": "%s", "amount": 20000}""".formatted(txnId, vpa);
        return http.postForObject("/debit", new HttpEntity<>(body, headers), BankResponse.class);
    }

    /** The JSON log lines written by the bank's operations service that mention this txnId. */
    private static List<String> opsLines(CapturedOutput output, String txnId) {
        return output.getOut().lines()
                .filter(l -> l.contains("\"logger_name\":\"com.payswitch.bank.service.BankOperationsService\""))
                .filter(l -> l.contains(txnId))
                .toList();
    }

    @Test
    void theTxnIdHeaderEndsUpAsAFieldInTheBanksLogLines(CapturedOutput output) {
        String ravi = newCustomer(500_000);
        String txn = newTxnId();

        assertEquals(BankResult.SUCCESS, debit(txn, ravi, txn).result());

        List<String> lines = opsLines(output, txn);
        assertEquals(1, lines.size(), "one log line for the debit: " + lines);
        assertTrue(lines.get(0).contains("\"txnId\":\"" + txn + "\""), lines.get(0));
        assertTrue(lines.get(0).contains("\"service\":\"bank-service-BANKA\""), lines.get(0));
    }

    @Test
    void aRequestWithoutTheHeaderDoesNotInheritAnEarlierTxnId(CapturedOutput output) {
        String ravi = newCustomer(500_000);
        String first = newTxnId();
        String second = newTxnId();

        debit(first, ravi, first);
        debit(second, ravi, null);                   // no header: nothing to put in the MDC

        String line = opsLines(output, second).get(0);
        assertFalse(line.contains("\"txnId\""), "no txnId field at all, and certainly not " + first + ": " + line);
    }

    @Test
    void prometheusShowsTheBanksCountersWithFixedLabelsOnly() {
        String ravi = newCustomer(500_000);
        String txn = newTxnId();
        long opsBefore = counter("bank_ops_total", "op=\"DEBIT\",result=\"SUCCESS\"");
        long replaysBefore = counter("bank_idempotent_replays_total", "op=\"DEBIT\"");

        debit(txn, ravi, txn);
        debit(txn, ravi, txn);                       // the switch retried: answered from the saved response

        assertEquals(opsBefore + 1, counter("bank_ops_total", "op=\"DEBIT\",result=\"SUCCESS\""));
        assertEquals(replaysBefore + 1, counter("bank_idempotent_replays_total", "op=\"DEBIT\""));
        String scrape = http.getForObject("/actuator/prometheus", String.class);
        assertTrue(scrape.contains("bank_fenced_rejections_total{application=\"bank-service\",bank=\"BANKA\",op=\"CREDIT\"}"),
                "rare counters exist at 0 from the start, so rate() sees their first increment");
        assertFalse(scrape.contains(txn), "a txnId must never appear in metrics (cardinality)");
        assertFalse(scrape.contains(ravi), "a VPA must never appear in metrics");
    }

    /** Reads one counter from /actuator/prometheus; 0 if it does not exist yet. */
    private long counter(String name, String labels) {
        String scrape = http.getForObject("/actuator/prometheus", String.class);
        // e.g. bank_ops_total{application="bank-service",bank="BANKA",op="DEBIT",result="SUCCESS"} 3.0
        Matcher m = Pattern.compile("(?m)^" + name + "\\{[^}]*" + Pattern.quote(labels) + "[^}]*} (\\S+)$")
                .matcher(scrape);
        return m.find() ? (long) Double.parseDouble(m.group(1)) : 0;
    }
}

package com.payswitch.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.payswitch.common.pay.PayState;
import com.payswitch.common.pay.RespPay;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Phase 11: one payment, followed through all three hops by its txnId alone.
 *
 * <p>The PSP makes up the txnId; nobody passes it to the bank's logger by hand. If a bank log line
 * carries it, it travelled PSP -> switch -> bank in the X-Txn-Id header and was put into the MDC on
 * the other side. (All services run in this one JVM, so each line is told apart by logger_name.)
 */
@ExtendWith(OutputCaptureExtension.class)
class TraceEndToEndTest {

    private static final PaymentSystem system = PaymentSystem.shared();

    @Test
    void oneTxnIdFindsThePaymentsLogLinesInEveryService(CapturedOutput output) {
        system.chaosOff();
        RespPay resp = RestClient.create().post().uri(system.pspUrl() + "/pay")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("payerVpa", "meena@banka", "payeeVpa", "kiran@bankb", "amount", "1.00"))
                .retrieve().body(RespPay.class);
        assertEquals(PayState.SUCCESS, resp.state());

        List<String> lines = output.getOut().lines()
                .filter(l -> l.contains("\"txnId\":\"" + resp.txnId() + "\"")).toList();

        assertTrue(lines.stream().anyMatch(l -> l.contains("\"logger_name\":\"com.payswitch.psp.")), "PSP: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.contains("\"logger_name\":\"com.payswitch.switchservice.")),
                "switch: " + lines);
        long bankLines = lines.stream()
                .filter(l -> l.contains("\"logger_name\":\"com.payswitch.bank.service.BankOperationsService\""))
                .count();
        assertEquals(2, bankLines, "the debit at Bank A and the credit at Bank B, both tagged: " + lines);
    }
}

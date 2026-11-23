package com.payswitch.psp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payswitch.common.pay.PayState;
import com.payswitch.common.pay.ReqPay;
import com.payswitch.common.pay.RespPay;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

/** Phase 11, PSP side: RED metrics for POST /pay, and the txnId in the PSP's own log lines. */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability(tracing = false)
@ExtendWith(OutputCaptureExtension.class)
class PspObservabilityTest {

    private static final String RAVI_PAYS_ANU_200 = """
            {"payerVpa": "ravi@banka", "payeeVpa": "anu@bankb", "amount": "200.00"}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SwitchClient switchClient;

    private String pay(int expectedStatus) throws Exception {
        String body = mvc.perform(post("/pay").contentType(MediaType.APPLICATION_JSON).content(RAVI_PAYS_ANU_200))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body).path("txnId").asText();
    }

    private double value(String name, String label) throws Exception {
        String scrape = mvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getContentAsString();
        Matcher m = Pattern.compile("(?m)^" + name + "\\{[^}]*" + Pattern.quote(label) + "[^}]*} (\\S+)$")
                .matcher(scrape);
        return m.find() ? Double.parseDouble(m.group(1)) : 0;
    }

    @Test
    void onePaymentIsCountedAndTimedByResult(CapturedOutput output) throws Exception {
        when(switchClient.pay(any())).thenAnswer(call ->
                new RespPay(((ReqPay) call.getArgument(0)).txnId(), PayState.SUCCESS, "CREDIT_OK"));
        double before = value("psp_requests_total", "result=\"SUCCESS\"");

        String txnId = pay(200);

        assertEquals(before + 1, value("psp_requests_total", "result=\"SUCCESS\""));
        assertTrue(value("psp_request_seconds_count", "result=\"SUCCESS\"") >= 1);
        String scrape = mvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getContentAsString();
        assertTrue(scrape.contains("psp_request_seconds_bucket{"), "a histogram, for p95/p99");
        assertFalse(scrape.contains(txnId));

        String line = output.getOut().lines().filter(l -> l.contains("switch answered SUCCESS")).findFirst().orElseThrow();
        assertTrue(line.contains("\"txnId\":\"" + txnId + "\""), line);
        assertTrue(line.contains("\"service\":\"psp-service\""), line);
    }

    @Test
    void aSwitchOutageIsCountedAsAnError() throws Exception {
        when(switchClient.pay(any())).thenThrow(new ResourceAccessException("Connection refused"));
        double before = value("psp_requests_total", "result=\"SWITCH_UNAVAILABLE\"");

        pay(502);

        assertEquals(before + 1, value("psp_requests_total", "result=\"SWITCH_UNAVAILABLE\""));
    }
}

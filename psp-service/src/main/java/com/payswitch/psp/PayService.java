package com.payswitch.psp;

import com.payswitch.common.pay.ReqPay;
import com.payswitch.common.pay.RespPay;
import com.payswitch.observability.TxnMdc;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Making one payment: new txnId, rupees to paise, ask the switch, count and time it. Used by
 * POST /pay AND by the demo's load runner, so both go through exactly the same code.
 */
@Service
public class PayService {

    private static final Logger log = LoggerFactory.getLogger(PayService.class);

    private final SwitchClient switchClient;
    private final MeterRegistry metrics;

    public PayService(SwitchClient switchClient, MeterRegistry metrics) {
        this.switchClient = switchClient;
        this.metrics = metrics;
    }

    /**
     * Ravi taps "Pay Rs 200 to anu@bankb".
     *
     * <p>The txnId is born here, so this is where it goes into the MDC: the PSP's own log lines carry
     * it, and the X-Txn-Id interceptor sends it to the switch with the request.
     */
    public RespPay pay(PayRequest request) {
        long start = System.nanoTime();
        String result = "ERROR";                                      // until we know better
        String txnId = newTxnId();
        try (TxnMdc.Scope mdc = TxnMdc.txn(txnId)) {
            try {
                long paise = RupeeAmount.toPaise(request.amount());  // rupees -> paise, here only
                ReqPay reqPay = new ReqPay(txnId, request.payerVpa(), request.payeeVpa(), paise);
                RespPay answer = switchClient.pay(reqPay);
                result = answer.state().name();
                log.info("Txn {}: switch answered {} ({})", txnId, answer.state(), answer.reason());
                return answer;
            } catch (ResourceAccessException | HttpServerErrorException e) {
                result = "SWITCH_UNAVAILABLE";
                log.warn("Txn {}: switch unavailable ({})", txnId, e.getMessage());
                throw new SwitchUnavailableException(txnId, e);
            } catch (IllegalArgumentException | HttpClientErrorException e) {
                result = "REJECTED";                                  // bad amount or VPA: nothing moved
                throw e;
            } finally {
                record(result, System.nanoTime() - start);
            }
        }
    }

    /**
     * psp_requests_total{result} (COUNTER) and psp_request_seconds{result} (HISTOGRAM): the RED
     * metrics of the PSP. Rate = requests/s, Errors = result SWITCH_UNAVAILABLE / ERROR,
     * Duration = the histogram. result is one of a handful of values, never the txnId.
     */
    private void record(String result, long nanos) {
        Counter.builder("psp.requests").description("POST /pay requests by result")
                .tag("result", result).register(metrics).increment();
        Timer.builder("psp.request").description("Time to answer POST /pay")
                .tag("result", result)
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofSeconds(30))
                .register(metrics)
                .record(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    /**
     * A random 128-bit id: unique without asking a database or another server.
     * 35 characters, e.g. TXN3F2A9C0E5B7D4A1F8E6C2B9D0A7F4E31.
     */
    static String newTxnId() {
        return "TXN" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }
}

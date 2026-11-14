package com.payswitch.switchservice.bank;

import com.payswitch.common.bank.BankResponse;
import com.payswitch.common.bank.BankResult;
import com.payswitch.common.bank.CreditRequest;
import com.payswitch.common.bank.DebitRequest;
import com.payswitch.common.bank.OpType;
import com.payswitch.common.bank.ReverseRequest;
import com.payswitch.switchservice.routing.BankRoute;
import com.payswitch.switchservice.routing.SwitchProperties;
import java.io.EOFException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The switch's HTTP calls to a bank, and the ONE place that decides what a bank's answer means.
 *
 * <p>Every call returns a {@link BankCallResult}; this class never throws. The rule is strict:
 * <ul>
 *   <li>SUCCESS only if the bank sent HTTP 2xx with a readable body saying SUCCESS for this
 *       exact txnId and operation.</li>
 *   <li>DECLINED only if the bank sent HTTP 2xx with a readable body saying DECLINED, same check.</li>
 *   <li>Everything else is UNKNOWN. We never guess that something "probably didn't happen".</li>
 * </ul>
 *
 * <p>Both timeouts are always set. Without them a bank that stops answering would hold a
 * switch thread forever, and enough of those would freeze the whole switch.
 */
@Component
public class BankClient {

    private static final Logger log = LoggerFactory.getLogger(BankClient.class);

    private final RestClient http;

    public BankClient(RestClient.Builder builder, SwitchProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.http().connectTimeout());
        factory.setReadTimeout(properties.http().readTimeout());
        this.http = builder.requestFactory(factory).build();
    }

    public BankCallResult debit(BankRoute bank, DebitRequest request) {
        return call(bank, "/debit", request, request.txnId(), OpType.DEBIT);
    }

    public BankCallResult credit(BankRoute bank, CreditRequest request) {
        return call(bank, "/credit", request, request.txnId(), OpType.CREDIT);
    }

    public BankCallResult reverse(BankRoute bank, ReverseRequest request) {
        return call(bank, "/reverse", request, request.txnId(), OpType.REVERSE);
    }

    private BankCallResult call(BankRoute bank, String path, Object body, String txnId, OpType op) {
        BankCallResult result;
        try {
            result = http.post()
                    .uri(bank.url() + path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            return BankCallResult.unknown("HTTP " + response.getStatusCode().value());
                        }
                        return classify(response.bodyTo(BankResponse.class), txnId, op);
                    });
        } catch (RestClientException e) {
            result = BankCallResult.unknown(describe(e));
        } catch (RuntimeException e) {
            result = BankCallResult.unknown("unexpected error: " + e.getClass().getSimpleName());
        }
        if (result.outcome() == BankCallOutcome.UNKNOWN) {
            log.warn("{} {} {} -> UNKNOWN ({})", bank.code(), op, txnId, result.detail());
        }
        return result;
    }

    /** A 2xx body: is it a clear yes, a clear no, or not trustworthy? */
    static BankCallResult classify(BankResponse body, String txnId, OpType op) {
        if (body == null) {
            return BankCallResult.unknown("empty response");
        }
        if (body.result() == null) {
            return BankCallResult.unknown("unreadable response");
        }
        if (!txnId.equals(body.txnId()) || op != body.op()) {
            // An answer about some OTHER request must never decide this one.
            return BankCallResult.unknown("mismatched response");
        }
        return body.result() == BankResult.SUCCESS
                ? BankCallResult.success()
                : BankCallResult.declined(body.reason());
    }

    /** Turns a failed call into a short reason. Looks through the whole cause chain. */
    static String describe(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            String message = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
            if (t instanceof SocketTimeoutException) {
                return message.contains("connect") ? "connect timeout" : "timeout";
            }
            if (t instanceof ConnectException) {
                return "connection refused";
            }
            if (t instanceof UnknownHostException) {
                return "unknown host";
            }
            if (t instanceof SocketException) {
                return message.contains("reset") ? "connection reset" : "connection error";
            }
            if (t instanceof EOFException || message.contains("unexpected end of file")) {
                return "connection closed";
            }
        }
        return "unreadable response";
    }
}

package com.payswitch.observability;

import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Runs before every outgoing RestClient call: if this thread is working on a payment (txnId in the
 * MDC), send it along as the X-Txn-Id header. That is how the id travels PSP -> switch -> bank
 * without any business code having to remember it.
 *
 * <p>A header the caller set explicitly is never overwritten.
 */
public class TxnIdForwardingInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String txnId = TxnMdc.currentTxnId();
        if (txnId != null && !request.getHeaders().containsKey(TxnMdc.HEADER)) {
            request.getHeaders().set(TxnMdc.HEADER, txnId);
        }
        return execution.execute(request, body);
    }
}

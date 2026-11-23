package com.payswitch.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Runs before every HTTP request: if the caller sent an X-Txn-Id header, put that txnId into the
 * MDC so every log line written while handling the request carries it.
 *
 * <p>Afterwards (finally) the MDC is put back EXACTLY as it was before the request. Tomcat reuses
 * its threads, so without this the next request on the same thread would log the wrong txnId.
 */
public class TxnIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Map<String, String> before = MDC.getCopyOfContextMap();
        try {
            String txnId = request.getHeader(TxnMdc.HEADER);
            if (TxnMdc.isValid(txnId)) {
                MDC.put(TxnMdc.TXN_ID, txnId);
            }
            chain.doFilter(request, response);
        } finally {
            if (before == null) {
                MDC.clear();
            } else {
                MDC.setContextMap(before);
            }
        }
    }
}

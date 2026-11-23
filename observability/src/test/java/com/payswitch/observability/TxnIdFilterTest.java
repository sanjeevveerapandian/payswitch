package com.payswitch.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.servlet.ServletException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TxnIdFilterTest {

    private final TxnIdFilter filter = new TxnIdFilter();

    @AfterEach
    void cleanUp() {
        MDC.clear();
    }

    /** Runs one request through the filter; returns the txnId the handler saw in the MDC. */
    private String handle(String header) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/debit");
        if (header != null) {
            request.addHeader(TxnMdc.HEADER, header);
        }
        AtomicReference<String> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(),
                new MockFilterChain(new jakarta.servlet.http.HttpServlet() { }, (req, res, chain) ->
                        seen.set(MDC.get(TxnMdc.TXN_ID))));
        return seen.get();
    }

    @Test
    void theHeaderIsInTheMdcDuringTheRequestAndGoneAfterIt() throws Exception {
        assertEquals("TXN42", handle("TXN42"));
        assertNull(MDC.get(TxnMdc.TXN_ID), "the next request on this thread must not inherit TXN42");
    }

    @Test
    void theNextRequestWithoutAHeaderSeesNoTxnId() throws Exception {
        handle("TXN42");
        assertNull(handle(null));
    }

    @Test
    void whatWasInTheMdcBeforeIsPutBack() throws Exception {
        MDC.put("other", "keep me");
        handle("TXN42");
        assertEquals("keep me", MDC.get("other"));
        assertNull(MDC.get(TxnMdc.TXN_ID));
    }

    @Test
    void cleanedUpEvenWhenTheRequestFails() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/debit");
        request.addHeader(TxnMdc.HEADER, "TXN42");
        assertThrows(ServletException.class, () -> filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> {
                    throw new ServletException("boom");
                }));
        assertNull(MDC.get(TxnMdc.TXN_ID));
    }

    @Test
    void aHeaderThatCouldForgeLogLinesIsIgnored() throws Exception {
        assertNull(handle("TXN1\",\"level\":\"ERROR"));
        assertNull(handle("TXN1\nFAKE LINE"));
        assertNull(handle("x".repeat(65)));
    }
}

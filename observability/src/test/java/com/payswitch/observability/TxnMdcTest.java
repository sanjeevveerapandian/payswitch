package com.payswitch.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.http.HttpStatus;

class TxnMdcTest {

    @AfterEach
    void cleanUp() {
        MDC.clear();
    }

    @Test
    void aScopeRestoresWhateverWasThereBefore() {
        try (TxnMdc.Scope outer = TxnMdc.txn("TXN1")) {
            TxnMdc.state("TXN1", "DEBIT_SENT");
            try (TxnMdc.Scope inner = TxnMdc.txn("TXN2")) {
                assertEquals("TXN2", MDC.get(TxnMdc.TXN_ID));
                assertNull(MDC.get(TxnMdc.STATE), "TXN1's state must not be logged for TXN2");
            }
            assertEquals("TXN1", MDC.get(TxnMdc.TXN_ID));
            assertEquals("DEBIT_SENT", MDC.get(TxnMdc.STATE));
        }
        assertNull(MDC.get(TxnMdc.TXN_ID));
        assertNull(MDC.get(TxnMdc.STATE));
    }

    @Test
    void stateIsOnlyRecordedForThePaymentInScope() {
        try (TxnMdc.Scope s = TxnMdc.txn("TXN1")) {
            TxnMdc.state("TXN9", "SUCCESS");
            assertNull(MDC.get(TxnMdc.STATE));
        }
    }

    @Test
    void theInterceptorSendsTheTxnIdOfTheCurrentThread() throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create("http://bank/debit"));
        try (TxnMdc.Scope s = TxnMdc.txn("TXN7")) {
            new TxnIdForwardingInterceptor().intercept(request, new byte[0],
                    (req, body) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK));
        }
        assertEquals("TXN7", request.getHeaders().getFirst(TxnMdc.HEADER));
    }

    @Test
    void noPaymentInScopeMeansNoHeader() throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.POST, URI.create("http://bank/recon"));
        new TxnIdForwardingInterceptor().intercept(request, new byte[0],
                (req, body) -> new MockClientHttpResponse(new byte[0], HttpStatus.OK));
        assertTrue(request.getHeaders().get(TxnMdc.HEADER) == null);
    }
}

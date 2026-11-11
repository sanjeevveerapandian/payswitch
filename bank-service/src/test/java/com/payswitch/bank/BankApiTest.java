package com.payswitch.bank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.payswitch.common.bank.BalanceResponse;
import com.payswitch.common.bank.BankResponse;
import com.payswitch.common.bank.BankResult;
import com.payswitch.common.bank.DeclineReason;
import com.payswitch.common.bank.StatusResponse;
import com.payswitch.common.bank.TxnStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** The same behaviour, but through real HTTP + JSON, to check status codes and error bodies. */
class BankApiTest extends BankIntegrationTest {

    @Autowired
    private TestRestTemplate http;

    @Test
    void debitOverHttpSucceeds() {
        String ravi = newCustomer(500_000);
        String txn = newTxnId();

        ResponseEntity<BankResponse> r = post("/debit", """
                {"txnId": "%s", "vpa": "%s", "amount": 20000}""".formatted(txn, ravi), BankResponse.class);

        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals(BankResult.SUCCESS, r.getBody().result());
        assertEquals(480_000, http.getForObject("/accounts/" + ravi + "/balance", BalanceResponse.class).balance());
        assertEquals(TxnStatus.SUCCESS,
                http.getForObject("/status/" + txn + "?op=DEBIT", StatusResponse.class).status());
    }

    @Test
    void businessDeclineIsA200WithReasonNotA500() {
        ResponseEntity<BankResponse> r = post("/debit", """
                {"txnId": "%s", "vpa": "nobody@banka", "amount": 20000}""".formatted(newTxnId()),
                BankResponse.class);

        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals(BankResult.DECLINED, r.getBody().result());
        assertEquals(DeclineReason.UNKNOWN_VPA, r.getBody().reason());
    }

    @Test
    void malformedRequestIsA400WithAClearMessage() {
        ResponseEntity<String> zero = post("/debit", """
                {"txnId": "t1", "vpa": "ravi@banka", "amount": 0}""", String.class);
        ResponseEntity<String> badOp = http.getForEntity("/status/t1?op=WITHDRAW", String.class);

        assertEquals(HttpStatus.BAD_REQUEST, zero.getStatusCode());
        assertTrue(zero.getBody().contains("amount must be between"), zero.getBody());
        assertEquals(HttpStatus.BAD_REQUEST, badOp.getStatusCode());
    }

    @Test
    void balanceOfUnknownVpaIsA404() {
        assertEquals(HttpStatus.NOT_FOUND,
                http.getForEntity("/accounts/nobody@banka/balance", String.class).getStatusCode());
    }

    private <T> ResponseEntity<T> post(String path, String json, Class<T> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity(path, new HttpEntity<>(json, headers), type);
    }
}

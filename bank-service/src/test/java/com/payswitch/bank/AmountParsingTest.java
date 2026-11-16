package com.payswitch.bank;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Review finding #1: amounts are whole paise. A decimal in the JSON must be REFUSED, never quietly
 * cut down (Jackson's default turns 20000.9 into 20000).
 */
class AmountParsingTest extends BankIntegrationTest {

    @Autowired
    private TestRestTemplate http;

    @Test
    void aDecimalDebitAmountIsRefusedAndNothingMoves() {
        String ravi = newCustomer(500_000);

        ResponseEntity<String> r = post("/debit", """
                {"txnId": "%s", "vpa": "%s", "amount": 20000.9}""".formatted(newTxnId(), ravi));

        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode(), r.getBody());
        assertEquals(500_000, balance(ravi));
    }

    @Test
    void aDecimalCreditAmountIsRefusedAndNothingMoves() {
        String anu = newCustomer(100_000);

        ResponseEntity<String> r = post("/credit", """
                {"txnId": "%s", "vpa": "%s", "amount": 99.99}""".formatted(newTxnId(), anu));

        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode(), r.getBody());
        assertEquals(100_000, balance(anu));
    }

    private ResponseEntity<String> post(String path, String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity(path, new HttpEntity<>(json, headers), String.class);
    }
}

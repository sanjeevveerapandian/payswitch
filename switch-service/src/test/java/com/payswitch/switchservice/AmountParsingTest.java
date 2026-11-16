package com.payswitch.switchservice;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payswitch.switchservice.bank.BankClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Review finding #1: a decimal amount in ReqPay is refused, and no bank is ever called. */
@AutoConfigureMockMvc
class AmountParsingTest extends SwitchIntegrationTest {

    @MockitoBean
    private BankClient banks;

    @Autowired
    private MockMvc mvc;

    @Test
    void aDecimalAmountIsRefused() throws Exception {
        mvc.perform(post("/pay").contentType(MediaType.APPLICATION_JSON).content("""
                        {"txnId": "%s", "payerVpa": "ravi@banka", "payeeVpa": "anu@bankb", "amount": 20000.9}
                        """.formatted(newTxnId())))
                .andExpect(status().isBadRequest());

        verify(banks, never()).debit(any(), any());
    }
}

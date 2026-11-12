package com.payswitch.psp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payswitch.common.pay.ReqPay;
import com.payswitch.common.pay.RespPay;
import com.payswitch.common.pay.TxnDetails;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

/** The PSP's own behaviour, with the switch replaced by a fake. */
@SpringBootTest
@AutoConfigureMockMvc
class PspApiTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private SwitchClient switchClient;

    private static final String RAVI_PAYS_ANU_200 = """
            {"payerVpa": "ravi@banka", "payeeVpa": "anu@bankb", "amount": "200.00"}
            """;

    @Test
    void payConvertsRupeesToPaiseAndForwardsToTheSwitch() throws Exception {
        when(switchClient.pay(any())).thenAnswer(call -> {
            ReqPay sent = call.getArgument(0);
            return new RespPay(sent.txnId(), "SUCCESS", "CREDIT_OK");
        });

        mvc.perform(post("/pay").contentType(MediaType.APPLICATION_JSON).content(RAVI_PAYS_ANU_200))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUCCESS"))
                .andExpect(jsonPath("$.reason").value("CREDIT_OK"))
                .andExpect(jsonPath("$.txnId").isNotEmpty());

        ArgumentCaptor<ReqPay> sent = ArgumentCaptor.forClass(ReqPay.class);
        verify(switchClient).pay(sent.capture());
        assertEquals("ravi@banka", sent.getValue().payerVpa());
        assertEquals("anu@bankb", sent.getValue().payeeVpa());
        assertEquals(20_000, sent.getValue().amount());              // Rs 200.00 -> 20000 paise
        assertTrue(sent.getValue().txnId().matches("TXN[0-9A-F]{32}"), sent.getValue().txnId());
    }

    @Test
    void everyPaymentGetsItsOwnTxnId() throws Exception {
        when(switchClient.pay(any())).thenAnswer(call ->
                new RespPay(((ReqPay) call.getArgument(0)).txnId(), "SUCCESS", "CREDIT_OK"));

        mvc.perform(post("/pay").contentType(MediaType.APPLICATION_JSON).content(RAVI_PAYS_ANU_200));
        mvc.perform(post("/pay").contentType(MediaType.APPLICATION_JSON).content(RAVI_PAYS_ANU_200));

        ArgumentCaptor<ReqPay> sent = ArgumentCaptor.forClass(ReqPay.class);
        verify(switchClient, times(2)).pay(sent.capture());
        List<ReqPay> both = sent.getAllValues();
        assertNotEquals(both.get(0).txnId(), both.get(1).txnId());
    }

    @Test
    void badAmountIsA400AndTheSwitchIsNeverCalled() throws Exception {
        for (String amount : List.of("0", "-5", "200.505", "100000.01", "abc")) {
            mvc.perform(post("/pay").contentType(MediaType.APPLICATION_JSON).content("""
                            {"payerVpa": "ravi@banka", "payeeVpa": "anu@bankb", "amount": "%s"}
                            """.formatted(amount)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/pay").contentType(MediaType.APPLICATION_JSON).content("""
                        {"payerVpa": "ravi@banka", "payeeVpa": "anu@bankb"}
                        """))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/pay").contentType(MediaType.APPLICATION_JSON).content("""
                        {"payeeVpa": "anu@bankb", "amount": "200"}
                        """))
                .andExpect(status().isBadRequest());

        verify(switchClient, never()).pay(any());
    }

    @Test
    void statusComesFromTheSwitch() throws Exception {
        when(switchClient.details("TXN1")).thenReturn(new TxnDetails("TXN1", "ravi@banka", "anu@bankb",
                "BANKA", "BANKB", 20_000, "REVERSED", "REVERSAL_OK", List.of()));

        mvc.perform(get("/pay/TXN1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.txnId").value("TXN1"))
                .andExpect(jsonPath("$.state").value("REVERSED"))
                .andExpect(jsonPath("$.reason").value("REVERSAL_OK"));
    }

    @Test
    void ifTheSwitchDoesNotAnswerWeSay502AndGiveTheTxnIdToCheckLater() throws Exception {
        when(switchClient.pay(any())).thenThrow(new ResourceAccessException("Read timed out"));

        mvc.perform(post("/pay").contentType(MediaType.APPLICATION_JSON).content(RAVI_PAYS_ANU_200))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.txnId").isNotEmpty());
    }
}

package com.payswitch.psp;

import com.payswitch.common.pay.ReqPay;
import com.payswitch.common.pay.RespPay;
import com.payswitch.common.pay.TxnDetails;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** The PSP's HTTP calls to the switch, always with a connect and a read timeout. */
@Component
public class SwitchClient {

    private final RestClient http;

    public SwitchClient(RestClient.Builder builder, PspProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.http().connectTimeout());
        factory.setReadTimeout(properties.http().readTimeout());
        this.http = builder.baseUrl(properties.switchUrl().toString()).requestFactory(factory).build();
    }

    public RespPay pay(ReqPay request) {
        return http.post().uri("/pay")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(RespPay.class);
    }

    public TxnDetails details(String txnId) {
        return http.get().uri("/txns/{txnId}", txnId)
                .retrieve()
                .body(TxnDetails.class);
    }
}

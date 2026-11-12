package com.payswitch.switchservice.bank;

import com.payswitch.common.bank.BankResponse;
import com.payswitch.common.bank.CreditRequest;
import com.payswitch.common.bank.DebitRequest;
import com.payswitch.common.bank.ReverseRequest;
import com.payswitch.switchservice.routing.BankRoute;
import com.payswitch.switchservice.routing.SwitchProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The switch's HTTP calls to a bank. One RestClient serves every bank; the bank's base URL
 * comes from the route on each call.
 *
 * <p>Both timeouts are always set. Without them a bank that stops answering would hold a
 * switch thread forever, and enough of those would freeze the whole switch.
 */
@Component
public class BankClient {

    private final RestClient http;

    public BankClient(RestClient.Builder builder, SwitchProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.http().connectTimeout());
        factory.setReadTimeout(properties.http().readTimeout());
        this.http = builder.requestFactory(factory).build();
    }

    public BankResponse debit(BankRoute bank, DebitRequest request) {
        return post(bank, "/debit", request);
    }

    public BankResponse credit(BankRoute bank, CreditRequest request) {
        return post(bank, "/credit", request);
    }

    public BankResponse reverse(BankRoute bank, ReverseRequest request) {
        return post(bank, "/reverse", request);
    }

    private BankResponse post(BankRoute bank, String path, Object body) {
        BankResponse response = http.post()
                .uri(bank.url() + path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(BankResponse.class);
        if (response == null) {
            throw new IllegalStateException(bank.code() + " " + path + " returned an empty body");
        }
        return response;
    }
}

package com.payswitch.switchservice.settlement;

import com.payswitch.common.settlement.SettlementAdvice;
import com.payswitch.common.settlement.SettlementAdviceResponse;
import com.payswitch.switchservice.routing.BankRoute;
import com.payswitch.switchservice.routing.SwitchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Sends a settlement advice to one bank. Like BankClient, it never throws: it answers "the bank
 * confirmed exactly this" (true) or "not confirmed, send it again later" (false). Sending again
 * is always safe because the bank applies each cycle once.
 */
@Component
public class SettlementAdviceClient {

    private static final Logger log = LoggerFactory.getLogger(SettlementAdviceClient.class);

    private final RestClient http;

    public SettlementAdviceClient(RestClient.Builder builder, SwitchProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.http().connectTimeout());
        factory.setReadTimeout(properties.http().readTimeout());
        this.http = builder.requestFactory(factory).build();
    }

    public boolean send(BankRoute bank, SettlementAdvice advice) {
        try {
            SettlementAdviceResponse answer = http.post()
                    .uri(bank.url() + "/settlement/advice")
                    .body(advice)
                    .retrieve()
                    .body(SettlementAdviceResponse.class);
            boolean confirmed = answer != null && answer.cycleId() == advice.cycleId()
                    && answer.netAmount() == advice.netAmount();
            if (!confirmed) {
                log.warn("{} answered settlement advice {} with {}", bank.code(), advice, answer);
            }
            return confirmed;
        } catch (HttpClientErrorException.Conflict e) {
            // The bank already applied this cycle with a DIFFERENT amount. Retrying cannot fix that.
            log.error("SETTLEMENT CONFLICT at {} for {}: {}", bank.code(), advice, e.getResponseBodyAsString());
            return false;
        } catch (RestClientException e) {
            log.warn("Settlement advice {} to {} not confirmed: {}", advice, bank.code(), e.getMessage());
            return false;
        }
    }
}

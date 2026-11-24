package com.payswitch.switchservice.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.payswitch.common.bank.BalanceResponse;
import com.payswitch.switchservice.routing.BankRoute;
import com.payswitch.switchservice.routing.BankRouter;
import com.payswitch.switchservice.routing.SwitchProperties;
import com.payswitch.switchservice.routing.Vpa;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The demo's calls to the banks' admin endpoints (chaos settings, one-shot rules, balances).
 * Kept apart from the scenarios so tests can replace it, and so no payment code ever calls these.
 */
@Component
@ConditionalOnProperty(name = "switch.demo.enabled", havingValue = "true")
public class DemoBankAdmin {

    private static final Logger log = LoggerFactory.getLogger(DemoBankAdmin.class);

    private final BankRouter router;
    private final RestClient http;

    public DemoBankAdmin(BankRouter router, RestClient.Builder builder, SwitchProperties properties) {
        this.router = router;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.http().connectTimeout());
        factory.setReadTimeout(properties.http().readTimeout());
        this.http = builder.requestFactory(factory).build();
    }

    /** The NEXT {@code op} request at this bank gets {@code mode} (decline, error, drop_response). */
    public void armOnce(String bankCode, String op, String mode) {
        try {
            http.post().uri(bank(bankCode).url() + "/admin/chaos/once").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("op", op, "mode", mode)).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            throw new ScenarioService.ScenarioUnavailableException(bankCode
                    + " refused to arm chaos (is its 'chaos' profile on?): " + e.getMessage());
        }
    }

    /** Takes back a one-shot rule if no request used it. Best effort: a failure is only logged. */
    public void disarmOnce(String bankCode, String op) {
        try {
            http.delete().uri(bank(bankCode).url() + "/admin/chaos/once/" + op).retrieve().toBodilessEntity();
        } catch (RestClientException e) {
            log.warn("Could not take back the one-shot {} rule at {}: {}", op, bankCode, e.getMessage());
        }
    }

    public JsonNode chaos(String bankCode) {
        JsonNode settings = http.get().uri(bank(bankCode).url() + "/admin/chaos").retrieve().body(JsonNode.class);
        if (settings == null) {
            throw new ScenarioService.ScenarioUnavailableException(bankCode + " did not report its chaos settings");
        }
        return settings;
    }

    public void setChaos(String bankCode, JsonNode settings) {
        http.post().uri(bank(bankCode).url() + "/admin/chaos").contentType(MediaType.APPLICATION_JSON)
                .body(settings).retrieve().toBodilessEntity();
    }

    /** A customer's balance in paise, from their own bank. */
    public long balance(String vpa) {
        BankRoute route = router.route(Vpa.parse(vpa))
                .orElseThrow(() -> new ScenarioService.ScenarioUnavailableException("no bank for " + vpa));
        BalanceResponse r = http.get().uri(route.url() + "/accounts/{vpa}/balance", vpa).retrieve()
                .body(BalanceResponse.class);
        if (r == null) {
            throw new ScenarioService.ScenarioUnavailableException(vpa + ": no balance from " + route.code());
        }
        return r.balance();
    }

    private BankRoute bank(String code) {
        return router.byCode(code).orElseThrow(() -> new ScenarioService.ScenarioUnavailableException("no bank " + code));
    }
}

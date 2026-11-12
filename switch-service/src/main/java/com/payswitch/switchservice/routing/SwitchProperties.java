package com.payswitch.switchservice.routing;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from application.yml:
 *
 * <pre>
 * switch:
 *   http:  { connect-timeout: 2s, read-timeout: 5s }
 *   banks:
 *     banka: { code: BANKA, url: http://localhost:8081 }
 * </pre>
 *
 * @param http  timeouts for every call the switch makes to a bank
 * @param banks VPA handle (the part after @) -> the bank that owns it
 */
@ConfigurationProperties("switch")
public record SwitchProperties(Http http, Map<String, BankRoute> banks) {

    public SwitchProperties {
        if (http == null) {
            throw new IllegalArgumentException("switch.http timeouts must be configured");
        }
        if (banks == null || banks.isEmpty()) {
            throw new IllegalArgumentException("switch.banks must list at least one bank");
        }
        banks = Map.copyOf(banks);
    }

    /**
     * @param connectTimeout how long to wait to open a connection to the bank
     * @param readTimeout    how long to wait for the bank's answer once connected
     */
    public record Http(Duration connectTimeout, Duration readTimeout) {

        public Http {
            requirePositive(connectTimeout, "switch.http.connect-timeout");
            requirePositive(readTimeout, "switch.http.read-timeout");
        }

        private static void requirePositive(Duration value, String name) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException(name + " must be set and positive (no infinite waits)");
            }
        }
    }
}

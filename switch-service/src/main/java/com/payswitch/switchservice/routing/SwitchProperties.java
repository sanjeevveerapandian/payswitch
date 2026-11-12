package com.payswitch.switchservice.routing;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from application.yml:
 *
 * <pre>
 * switch:
 *   banks:
 *     banka: { code: BANKA, url: http://localhost:8081 }
 * </pre>
 *
 * @param banks VPA handle (the part after @) -> the bank that owns it
 */
@ConfigurationProperties("switch")
public record SwitchProperties(Map<String, BankRoute> banks) {

    public SwitchProperties {
        if (banks == null || banks.isEmpty()) {
            throw new IllegalArgumentException("switch.banks must list at least one bank");
        }
        banks = Map.copyOf(banks);
    }
}

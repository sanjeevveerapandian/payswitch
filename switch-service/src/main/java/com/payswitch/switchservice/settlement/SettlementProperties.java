package com.payswitch.switchservice.settlement;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bound from switch.settlement.* in application.yml.
 *
 * @param enabled  run the scheduled close
 * @param interval how often the open cycle is closed and settled
 */
@ConfigurationProperties("switch.settlement")
public record SettlementProperties(@DefaultValue("true") boolean enabled,
                                   @DefaultValue("1h") Duration interval) {
}

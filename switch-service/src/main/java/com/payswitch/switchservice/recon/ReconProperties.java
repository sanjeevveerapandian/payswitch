package com.payswitch.switchservice.recon;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bound from switch.recon.* in application.yml.
 *
 * @param enabled        run the scheduled job
 * @param cutoff         only reconcile payments created at least this long ago: newer ones may
 *                       still be in flight, and would show up as false mismatches
 * @param interval       the scheduled job reconciles one window of this length each time
 * @param bankClockSlack bank entries are fetched for the window widened by this much on both sides,
 *                       because a bank stamps an operation a little after the switch created it
 *                       (and the clocks of different machines never agree exactly)
 * @param pageSize       page size when reading /recon/entries from a bank
 * @param runLease       how long a run may hold its window before another run may take over
 */
@ConfigurationProperties("switch.recon")
public record ReconProperties(@DefaultValue("true") boolean enabled,
                              @DefaultValue("5m") Duration cutoff,
                              @DefaultValue("10m") Duration interval,
                              @DefaultValue("5m") Duration bankClockSlack,
                              @DefaultValue("500") int pageSize,
                              @DefaultValue("30m") Duration runLease) {

    public ReconProperties {
        if (pageSize < 1 || pageSize > 1000) {
            throw new IllegalArgumentException("switch.recon.page-size must be between 1 and 1000");
        }
    }
}

package com.payswitch.switchservice.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the switch finds out what happened when a bank's answer was UNKNOWN. Bound from
 * switch.resolution.* in application.yml.
 *
 * @param enabled      false = first attempt only, unknowns stay PENDING (the Phase 5 behaviour)
 * @param inlineWait   how long POST /pay waits for resolution before answering PENDING
 * @param stuckAfter   the background job only touches payments untouched for this long
 * @param interval     how often the background job runs
 * @param batchSize    how many stuck payments one job claims at a time
 * @param lease        after claiming a payment, nobody else may claim it for this long
 * @param runOnStartup also run the job once when the switch starts (crash recovery)
 */
@ConfigurationProperties("switch.resolution")
public record ResolutionProperties(boolean enabled, Duration inlineWait, Duration stuckAfter, Duration interval,
                                   int batchSize, Duration lease, boolean runOnStartup) {

    public ResolutionProperties {
        if (inlineWait == null || stuckAfter == null || interval == null || lease == null) {
            throw new IllegalArgumentException("switch.resolution durations must all be set");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("switch.resolution.batch-size must be at least 1");
        }
    }
}

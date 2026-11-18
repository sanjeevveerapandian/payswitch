package com.payswitch.switchservice.settlement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every {@code switch.settlement.interval}: first retry any cycle that is CLOSED but not SETTLED
 * (a bank was down last time), then close the open cycle and settle it.
 */
@Component
public class SettlementJob {

    private static final Logger log = LoggerFactory.getLogger(SettlementJob.class);

    private final SettlementService settlement;
    private final SettlementProperties props;

    public SettlementJob(SettlementService settlement, SettlementProperties props) {
        this.settlement = settlement;
        this.props = props;
    }

    @Scheduled(initialDelayString = "${switch.settlement.interval:1h}",
               fixedDelayString = "${switch.settlement.interval:1h}")
    public void onSchedule() {
        if (!props.enabled()) {
            return;
        }
        try {
            settlement.settleUnfinished();
            SettlementReport report = settlement.closeAndSettle(null);
            log.info("Scheduled settlement: cycle {} {} ({} payments, net moved {} of gross {} paise)",
                    report.cycleId(), report.status(), report.items().size(),
                    report.grossVsNet().netAmountMoved(), report.grossVsNet().grossAmount());
        } catch (RuntimeException e) {
            log.error("Scheduled settlement failed; it will run again next time", e);
        }
    }
}

package com.payswitch.switchservice.recon;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every {@code interval}, reconciles the previous window.
 *
 * <p>Windows are aligned to the interval (10:00-10:10, 10:10-10:20, ...) and end before
 * now minus cutoff. Because the same window always gets the same run, a timer firing twice, or
 * two copies of the switch both firing, cannot do anything twice.
 */
@Component
public class ReconJob {

    private static final Logger log = LoggerFactory.getLogger(ReconJob.class);

    private final ReconciliationService recon;
    private final ReconProperties props;

    public ReconJob(ReconciliationService recon, ReconProperties props) {
        this.recon = recon;
        this.props = props;
    }

    @Scheduled(initialDelayString = "${switch.recon.interval:10m}", fixedDelayString = "${switch.recon.interval:10m}")
    public void onSchedule() {
        if (!props.enabled()) {
            return;
        }
        long step = props.interval().toMillis();
        long end = Math.floorDiv(Instant.now().minus(props.cutoff()).toEpochMilli(), step) * step;
        Instant to = Instant.ofEpochMilli(end);
        Instant from = to.minus(props.interval());
        try {
            ReconReport report = recon.run(from, to);
            log.info("Recon {}..{}: {} {}", from, to, report.status(), report.counts());
        } catch (ReconUnavailableException e) {
            log.warn("Recon {}..{} skipped: {}", from, to, e.getMessage());
        }
    }
}

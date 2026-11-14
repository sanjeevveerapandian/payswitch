package com.payswitch.switchservice.service;

import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The safety net. Every few seconds, and once at startup, it finds payments stuck in DEBIT_SENT,
 * CREDIT_SENT or REVERSAL_SENT and resolves them with the same logic POST /pay uses.
 *
 * <p>Startup matters: if the switch crashed mid-payment, nothing else would ever look at that
 * payment again. Running once on startup is the crash recovery.
 *
 * <p>Many copies of the switch can run this at the same time: each batch is claimed with
 * SELECT ... FOR UPDATE SKIP LOCKED plus a lease, so no payment is handled by two at once.
 */
@Component
public class ResolverJob {

    private static final Logger log = LoggerFactory.getLogger(ResolverJob.class);
    private static final int MAX_BATCHES_PER_RUN = 100;

    private final TransactionService txns;
    private final PaymentFlow flow;
    private final ResolutionProperties props;

    public ResolverJob(TransactionService txns, PaymentFlow flow, ResolutionProperties props) {
        this.txns = txns;
        this.flow = flow;
        this.props = props;
    }

    @Scheduled(initialDelayString = "${switch.resolution.interval}", fixedDelayString = "${switch.resolution.interval}")
    public void onSchedule() {
        if (props.enabled()) {
            runOnce();
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (props.enabled() && props.runOnStartup()) {
            List<String> handled = runOnce();
            log.info("Startup recovery: resolved {} stuck payment(s).", handled.size());
        }
    }

    /**
     * Claims and resolves batches until no stuck payment is left to claim.
     *
     * @return the txnIds this run claimed (each exactly once)
     */
    public List<String> runOnce() {
        List<String> handled = new ArrayList<>();
        for (int batchNo = 0; batchNo < MAX_BATCHES_PER_RUN; batchNo++) {
            // Short transaction: lock a few rows (skipping any another resolver holds), lease them, commit.
            List<String> batch = txns.claimStuck(PaymentFlow.RESOLVABLE, props.stuckAfter(), props.batchSize(),
                    props.lease());
            if (batch.isEmpty()) {
                break;
            }
            // No transaction here: resolving calls banks over HTTP.
            for (String txnId : batch) {
                try {
                    flow.resolve(txnId);
                } catch (RuntimeException e) {
                    log.error("Txn {}: resolver failed; it will be retried when its lease runs out.", txnId, e);
                }
            }
            handled.addAll(batch);
        }
        return handled;
    }
}

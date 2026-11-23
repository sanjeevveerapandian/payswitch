package com.payswitch.switchservice.service;

import com.payswitch.observability.TxnMdc;
import com.payswitch.switchservice.domain.TxnState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The safety net. Every few seconds, and once at startup, it finds payments stuck in INITIATED,
 * DEBIT_SENT, DEBITED, CREDIT_SENT or REVERSAL_SENT and resolves them with the same logic
 * POST /pay uses. Much less often, it also checks DEEMED_APPROVED payments again with the
 * payee's bank.
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
        // Unfinished payments: every few seconds once they have been quiet for stuck-after.
        handled.addAll(claimAndResolve(PaymentFlow.STUCK, props.stuckAfter(), props.lease()));
        // DEEMED_APPROVED: much less often. The lease doubles as the wait until the next check.
        Duration recheck = props.deemedRecheckAfter();
        Duration deemedLease = recheck.compareTo(props.lease()) > 0 ? recheck : props.lease();
        handled.addAll(claimAndResolve(EnumSet.of(TxnState.DEEMED_APPROVED), recheck, deemedLease));
        return handled;
    }

    private List<String> claimAndResolve(Set<TxnState> states, Duration quietFor, Duration lease) {
        List<String> handled = new ArrayList<>();
        for (int batchNo = 0; batchNo < MAX_BATCHES_PER_RUN; batchNo++) {
            // Short transaction: lock a few rows (skipping any another resolver holds), lease them, commit.
            List<String> batch = txns.claimStuck(states, quietFor, props.batchSize(), lease);
            if (batch.isEmpty()) {
                break;
            }
            // No transaction here: resolving calls banks over HTTP.
            for (String txnId : batch) {
                if (Thread.currentThread().isInterrupted()) {
                    // Shutting down: stop. Claimed-but-untouched payments are picked up again when
                    // their lease runs out (or by the startup run after the restart).
                    return handled;
                }
                try (TxnMdc.Scope mdc = TxnMdc.txn(txnId)) {
                    flow.resolve(txnId);
                } catch (RuntimeException e) {
                    try (TxnMdc.Scope mdc = TxnMdc.txn(txnId)) {
                        log.error("Txn {}: resolver failed; it will be retried when its lease runs out.", txnId, e);
                    }
                }
            }
            handled.addAll(batch);
        }
        return handled;
    }
}

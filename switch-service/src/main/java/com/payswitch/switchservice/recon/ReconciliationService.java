package com.payswitch.switchservice.recon;

import com.payswitch.common.bank.OpType;
import com.payswitch.common.bank.ReconEntry;
import com.payswitch.common.bank.ReconStatus;
import com.payswitch.observability.TxnMdc;
import com.payswitch.switchservice.domain.Txn;
import com.payswitch.switchservice.domain.TxnState;
import com.payswitch.switchservice.domain.TxnStateMachine;
import com.payswitch.switchservice.metrics.SwitchMetrics;
import com.payswitch.switchservice.routing.BankRoute;
import com.payswitch.switchservice.routing.BankRouter;
import com.payswitch.switchservice.service.ConcurrentTransitionException;
import com.payswitch.switchservice.service.PaymentFlow;
import com.payswitch.switchservice.service.TransactionService;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Compares the switch's books with the banks' books for one time window.
 *
 * <p>One rule makes it safe to run as often as you like: a window has exactly ONE run, and a run
 * only ever handles txnIds it has no line for yet. So:
 * <ul>
 *   <li>running a finished window again changes nothing and reports the same thing;</li>
 *   <li>a run that crashed half way is simply continued by the next run of that window;</li>
 *   <li>payments that were too new last time are picked up when the window is run again.</li>
 * </ul>
 *
 * <p>The only money recon ever moves is finishing a DEEMED_APPROVED payment, and only on proof from
 * the payee's bank. Every other disagreement goes to a human.
 *
 * <p>Not @Transactional: it calls banks over HTTP. Each write is its own short transaction.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final ReconRepository repo;
    private final TransactionService txns;
    private final PaymentFlow flow;
    private final BankRouter router;
    private final BankReconClient bankRecon;
    private final ReconProperties props;
    private final SwitchMetrics metrics;

    public ReconciliationService(ReconRepository repo, TransactionService txns, PaymentFlow flow, BankRouter router,
                                 BankReconClient bankRecon, ReconProperties props, SwitchMetrics metrics) {
        this.metrics = metrics;
        this.repo = repo;
        this.txns = txns;
        this.flow = flow;
        this.router = router;
        this.bankRecon = bankRecon;
        this.props = props;
    }

    /** What each bank says, by bank code, then by txnId. */
    private record BankView(Map<String, Map<String, List<ReconEntry>>> byBank) {

        List<ReconEntry> of(String bankCode, String txnId) {
            if (bankCode == null) {
                return List.of();
            }
            return byBank.getOrDefault(bankCode, Map.of()).getOrDefault(txnId, List.of());
        }
    }

    /**
     * Reconciles payments CREATED in [from, to).
     *
     * @throws ReconUnavailableException if a bank could not be read; nothing is decided in that case
     */
    public ReconReport run(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
        // ONE horizon for the whole run: "old enough" means created before this instant. Asking the
        // clock twice (once when listing payments, again when counting the too-new ones) lost every
        // payment that aged past the cutoff while the run was working (Phase 10 chaos test).
        Instant youngest = txns.dbNow().minus(props.cutoff());
        long runId = repo.findOrCreateRun(from, to);
        if (!repo.claim(runId, props.runLease())) {
            log.info("Recon run {} for {}..{} is already running elsewhere; reporting as it stands.", runId, from, to);
            return report(runId);
        }

        // 1. The banks' view FIRST, complete, for the window widened by the clock slack.
        BankView banks = readBanks(from.minus(props.bankClockSlack()), to.plus(props.bankClockSlack()));

        // 2. Finish what a crashed run of this window started.
        for (String txnId : repo.deemedInProgress(runId)) {
            try (TxnMdc.Scope mdc = TxnMdc.txn(txnId)) {
                finishDeemed(runId, txnId, banks);
            }
        }

        // 3. Every switch payment in the window that is old enough and has no line yet.
        Set<String> done = repo.itemTxnIds(runId);
        for (Txn txn : txns.createdBetween(from, to, youngest)) {
            if (!done.contains(txn.txnId())) {
                try (TxnMdc.Scope mdc = TxnMdc.txn(txn.txnId())) {
                    TxnMdc.state(txn.txnId(), txn.state().name());
                    reconcile(runId, txn, banks);
                }
            }
        }

        // 4. Money a bank moved for a txnId the switch has never heard of.
        findBankOnly(runId, from, to, banks);

        int tooNew = txns.countTooRecent(from, to, youngest);
        Map<String, Integer> counts = new TreeMap<>(repo.counts(runId));
        counts.put("SKIPPED_TOO_NEW", tooNew);
        repo.finish(runId, tooNew > 0 ? "PARTIAL" : "COMPLETED", counts);
        return report(runId);
    }

    public ReconReport report(long runId) {
        ReconRepository.Run run = repo.run(runId).orElseThrow(() -> new ReconRunNotFoundException(runId));
        return new ReconReport(run.id(), run.windowFrom(), run.windowTo(), run.status(), run.counts(),
                repo.exceptions(runId));
    }

    private BankView readBanks(Instant from, Instant to) {
        Map<String, Map<String, List<ReconEntry>>> byBank = new HashMap<>();
        for (BankRoute bank : router.allBanks()) {
            Map<String, List<ReconEntry>> byTxn = new HashMap<>();
            for (ReconEntry e : bankRecon.fetchAll(bank, from, to, props.pageSize())) {
                byTxn.computeIfAbsent(e.txnId(), k -> new java.util.ArrayList<>()).add(e);
            }
            byBank.put(bank.code(), byTxn);
        }
        return new BankView(byBank);
    }

    private void reconcile(long runId, Txn txn, BankView banks) {
        String id = txn.txnId();
        List<ReconEntry> payer = banks.of(txn.payerBank(), id);
        List<ReconEntry> payee = banks.of(txn.payeeBank(), id);

        if (txn.state() == TxnState.DEEMED_APPROVED) {
            // Write the intention down BEFORE acting, like the switch saves DEBIT_SENT before a debit.
            // If we crash during the action, the next run finds this line and finishes the job.
            String view = ReconMatcher.describe(txn.payerBank(), payer) + " | " + ReconMatcher.describe(txn.payeeBank(), payee);
            repo.insertItem(runId, id, ReconCategory.RESOLVED_DEEMED, TxnState.DEEMED_APPROVED.name(), view, "IN_PROGRESS");
            finishDeemed(runId, id, banks);
            return;
        }

        ReconMatcher.Verdict verdict = ReconMatcher.compare(txn.state(), txn.amount(),
                txn.payerBank(), payer, txn.payeeBank(), payee);
        String action = verdict.category() == ReconCategory.MATCHED ? "NONE" : "NONE: manual review";
        if (repo.insertItem(runId, id, verdict.category(), txn.state().name(), verdict.bankView(), action)) {
            metrics.reconItem(verdict.category());
        }
        if (verdict.category().isException()) {
            log.warn("RECON {} txn {} ({}): {}", verdict.category(), id, txn.state(), verdict.bankView());
        }
    }

    /**
     * DEEMED_APPROVED: Ravi was debited, and when we asked, Anu's bank never answered clearly.
     * <ul>
     *   <li>Anu's bank now reports the credit, for the full amount: SUCCESS. No money moves.</li>
     *   <li>No credit in its records: ask it with fence=true FIRST. Only once it has promised the
     *       credit will never happen (FENCED) is refunding Ravi safe.</li>
     *   <li>A credit for a different amount: do nothing, a human must look.</li>
     * </ul>
     */
    private void finishDeemed(long runId, String txnId, BankView banks) {
        Txn txn = txns.get(txnId);
        if (txn.state() == TxnState.DEEMED_APPROVED) {
            long credited = ReconMatcher.moved(banks.of(txn.payeeBank(), txnId), OpType.CREDIT);
            if (credited == txn.amount()) {
                try {
                    txns.transition(txnId, txn.version(), TxnState.SUCCESS, "RECON_CREDIT_FOUND");
                } catch (ConcurrentTransitionException someoneElse) {
                    log.info("Txn {}: moved by someone else while recon worked on it.", txnId);
                }
            } else if (credited != 0) {
                repo.finishItem(runId, txnId, ReconCategory.AMOUNT_MISMATCH, txn.state().name(),
                        "NONE: manual review (credited " + credited + ", expected " + txn.amount() + ")", false);
                metrics.reconItem(ReconCategory.AMOUNT_MISMATCH);
                return;
            } else {
                // Status check WITH fence first, then (only if fenced) reverse. PaymentFlow does exactly that.
                flow.resolve(txnId);
            }
        }

        Txn after = txns.get(txnId);
        if (TxnStateMachine.isTerminal(after.state())) {
            repo.finishItem(runId, txnId, ReconCategory.RESOLVED_DEEMED, after.state().name(),
                    "MOVED_TO_" + after.state(), true);
            metrics.reconItem(ReconCategory.RESOLVED_DEEMED);
        } else {
            repo.finishItem(runId, txnId, ReconCategory.DEEMED_UNRESOLVED, after.state().name(),
                    "NONE: payee bank still not answering; manual review", false);
            metrics.reconItem(ReconCategory.DEEMED_UNRESOLVED);
            log.warn("RECON txn {} still {} after recon: payee bank not answering.", txnId, after.state());
        }
    }

    /**
     * Bank entries stamped inside [from, to) that MOVED money for a txnId the switch does not know at
     * all. (A txnId the switch knows, but created outside this window, belongs to another window.)
     */
    private void findBankOnly(long runId, Instant from, Instant to, BankView banks) {
        Set<String> candidates = new TreeSet<>();
        banks.byBank().values().forEach(byTxn -> byTxn.forEach((txnId, entries) -> {
            boolean movedMoneyInWindow = entries.stream().anyMatch(e -> e.status() == ReconStatus.SUCCESS
                    && !e.createdAt().isBefore(from) && e.createdAt().isBefore(to));
            if (movedMoneyInWindow) {
                candidates.add(txnId);
            }
        }));
        candidates.removeAll(repo.itemTxnIds(runId));
        candidates.removeAll(txns.existing(candidates));
        for (String txnId : candidates) {
            StringBuilder view = new StringBuilder();
            banks.byBank().forEach((bank, byTxn) -> {
                List<ReconEntry> entries = byTxn.getOrDefault(txnId, List.of());
                if (!entries.isEmpty()) {
                    view.append(view.isEmpty() ? "" : " | ").append(ReconMatcher.describe(bank, entries));
                }
            });
            try (TxnMdc.Scope mdc = TxnMdc.txn(txnId)) {
                if (repo.insertItem(runId, txnId, ReconCategory.BANK_ONLY, null, view.toString(),
                        "NONE: manual review (no switch txn)")) {
                    metrics.reconItem(ReconCategory.BANK_ONLY);
                }
                log.warn("RECON BANK_ONLY txn {}: {}", txnId, view);
            }
        }
    }
}

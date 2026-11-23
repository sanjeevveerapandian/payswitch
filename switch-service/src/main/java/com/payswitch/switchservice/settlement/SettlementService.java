package com.payswitch.switchservice.settlement;

import com.payswitch.common.settlement.SettlementAdvice;
import com.payswitch.switchservice.metrics.SwitchMetrics;
import com.payswitch.switchservice.routing.BankRoute;
import com.payswitch.switchservice.routing.BankRouter;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Settlement cycles: OPEN -> CLOSED -> SETTLED (or OPEN -> FAILED).
 *
 * <ol>
 *   <li><b>Close</b> (one DB transaction): every reconciled SUCCESS payment joins the open cycle,
 *       each bank's net position is computed and CHECKED to sum to zero, the cycle is CLOSED and a
 *       new one is opened. If the sum is not zero: FAILED, loud ERROR, and nothing is settled.</li>
 *   <li><b>Settle</b>: one entry in the central bank's ledger (one DB transaction), then an advice
 *       to every bank. Once every bank has confirmed, SETTLED.</li>
 * </ol>
 *
 * <p>Both steps can be repeated safely. Closing a cycle that is no longer OPEN changes nothing.
 * Settling posts the central entry only if the cycle has none, and only advises banks that have
 * not confirmed yet (and a bank applies each cycle once anyway).
 *
 * <p>Not @Transactional as a whole: settling calls banks over HTTP. TransactionTemplate marks
 * exactly the parts that must be atomic.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final SettlementRepository repo;
    private final NetPositionCalculator calculator;
    private final CentralLedger central;
    private final SettlementAdviceClient advice;
    private final BankRouter router;
    private final TransactionTemplate tx;
    private final SwitchMetrics metrics;

    public SettlementService(SettlementRepository repo, NetPositionCalculator calculator, CentralLedger central,
                             SettlementAdviceClient advice, BankRouter router, TransactionTemplate tx,
                             SwitchMetrics metrics) {
        this.metrics = metrics;
        this.repo = repo;
        this.calculator = calculator;
        this.central = central;
        this.advice = advice;
        this.router = router;
        this.tx = tx;
    }

    /** Close (the given cycle, or the open one if null), then settle it. What the API and the job call. */
    public SettlementReport closeAndSettle(Long cycleId) {
        long id = close(cycleId);
        if ("CLOSED".equals(repo.cycle(id).orElseThrow().status())) {
            return settle(id);
        }
        return report(id);
    }

    /**
     * @param cycleId the cycle to close, or null for the one that is open now
     * @return the id of the cycle that was closed (or was already closed)
     */
    public long close(Long cycleId) {
        return tx.execute(status -> {
            repo.lockForClosing();
            SettlementRepository.Cycle cycle = cycleId == null ? repo.openCycle()
                    : repo.cycle(cycleId).orElseThrow(() -> new SettlementCycleNotFoundException(cycleId));
            if (!"OPEN".equals(cycle.status())) {
                return cycle.id();                                    // closing twice changes nothing
            }

            int joined = repo.admitReconciled(cycle.id());
            List<SettlementItem> items = repo.items(cycle.id());
            TreeSet<String> banks = new TreeSet<>();
            router.allBanks().forEach(b -> banks.add(b.code()));
            Map<String, Long> nets = new TreeMap<>(calculator.compute(items, banks));
            long sum = nets.values().stream().reduce(0L, Math::addExact);

            repo.savePositions(cycle.id(), nets);
            if (sum != 0) {
                String reason = "net positions sum to " + sum + " paise, expected 0";
                repo.markFailed(cycle.id(), reason);
                log.error("SETTLEMENT CYCLE {} FAILED: {}. Positions {}. Nothing was settled; its {} payments "
                        + "roll into the next cycle. A person must investigate.", cycle.id(), reason, nets, items.size());
            } else {
                repo.markClosed(cycle.id());
                log.info("Settlement cycle {} closed: {} payments ({} new), positions {}", cycle.id(),
                        items.size(), joined, nets);
            }
            repo.openNewCycle();
            return cycle.id();
        });
    }

    /**
     * Moves the money for a CLOSED cycle. Safe to call again and again: SETTLED is a no-op.
     *
     * @throws IllegalStateException for a cycle that is still OPEN, or FAILED (never settled)
     */
    public SettlementReport settle(long cycleId) {
        // Settlement works on a whole cycle, not one payment: its log lines carry the cycleId instead.
        try (MDC.MDCCloseable mdc = MDC.putCloseable("cycleId", Long.toString(cycleId))) {
            return settleInScope(cycleId);
        }
    }

    private SettlementReport settleInScope(long cycleId) {
        String status = tx.execute(s -> {
            SettlementRepository.Cycle cycle = repo.lockCycle(cycleId)
                    .orElseThrow(() -> new SettlementCycleNotFoundException(cycleId));
            switch (cycle.status()) {
                case "OPEN" -> throw new IllegalStateException("Cycle " + cycleId + " is still OPEN: close it first");
                case "FAILED" -> throw new IllegalStateException("Cycle " + cycleId + " FAILED and is never settled");
                default -> { }
            }
            if ("CLOSED".equals(cycle.status()) && !central.isPosted(cycleId)) {
                Map<String, Long> nets = new TreeMap<>();
                repo.positions(cycleId).forEach(p -> nets.put(p.bankCode(), p.netAmount()));
                central.postSettlement(cycleId, nets);
            }
            return cycle.status();
        });
        if ("SETTLED".equals(status)) {
            return report(cycleId);
        }

        // The central bank has moved the money. Now tell each bank, so its books match.
        boolean allConfirmed = true;
        for (SettlementReport.Position p : repo.positions(cycleId)) {
            if (p.advised()) {
                continue;
            }
            BankRoute bank = router.byCode(p.bankCode()).orElse(null);
            if (bank != null && advice.send(bank, new SettlementAdvice(cycleId, p.netAmount()))) {
                repo.markAdvised(cycleId, p.bankCode());
            } else {
                allConfirmed = false;
            }
        }
        if (allConfirmed && repo.markSettled(cycleId)) {
            log.info("Settlement cycle {} SETTLED", cycleId);
            repo.positions(cycleId).forEach(p -> metrics.settlementNet(p.bankCode(), p.netAmount()));
        } else if (!allConfirmed) {
            log.warn("Settlement cycle {}: not every bank confirmed; it stays CLOSED and is retried.", cycleId);
        }
        return report(cycleId);
    }

    /**
     * Gauges live in memory, so after a restart the "last settlement" gauge would be empty until the
     * next cycle settles. Read it back from the database once at startup instead.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void publishLastSettledPositions() {
        repo.lastSettledCycle().ifPresent(id ->
                repo.positions(id).forEach(p -> metrics.settlementNet(p.bankCode(), p.netAmount())));
    }

    /** The most recently SETTLED cycle, if there is one (for the demo UI's Admin panel). */
    public java.util.Optional<SettlementReport> lastSettled() {
        return repo.lastSettledCycle().map(this::report);
    }

    /** Retries every cycle that is CLOSED but not yet SETTLED (e.g. a bank was down). */
    public void settleUnfinished() {
        for (long id : repo.closedCycles()) {
            settle(id);
        }
    }

    public SettlementReport report(long cycleId) {
        SettlementRepository.Cycle c = repo.cycle(cycleId)
                .orElseThrow(() -> new SettlementCycleNotFoundException(cycleId));
        List<SettlementItem> items = repo.items(cycleId);
        List<SettlementReport.Position> positions = repo.positions(cycleId);
        List<Long> nets = positions.stream().map(SettlementReport.Position::netAmount).toList();
        long sum = nets.stream().reduce(0L, Math::addExact);
        return new SettlementReport(c.id(), c.status(), c.openedAt(), c.closedAt(), c.settledAt(), c.failureReason(),
                items, positions, sum, GrossVsNet.of(items, nets));
    }
}

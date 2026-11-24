package com.payswitch.switchservice.demo;

import com.payswitch.common.pay.ReqPay;
import com.payswitch.common.pay.RespPay;
import com.payswitch.switchservice.service.PaymentOrchestrator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Phase 13: puts the six demo customers back to their starting balances, so the public demo never
 * runs dry (Ravi cannot pay once visitors have sent all his money to Anu's shop).
 *
 * <p>How the money invariant holds: no balance is ever SET. Every change is an ordinary payment
 * through {@link PaymentOrchestrator}, from a customer who has more than at the start to one who has
 * less ({@link ReseedPlan}). So the reseed obeys every rule a visitor's payment obeys: double-entry
 * at both banks, idempotency by txnId, crash recovery, reconciliation, net settlement. TOTAL MONEY
 * in the UI does not move, and the money auditor would pass.
 *
 * <p>Runs every night (switch.demo.reseed-cron, Indian time) and from the admin button.
 */
@Service
@ConditionalOnProperty(name = "switch.demo.enabled", havingValue = "true")
public class DemoReseedService {

    private static final Logger log = LoggerFactory.getLogger(DemoReseedService.class);

    /** Paise. Must match db/bank/seed/BANK?/V100__seed_customers.sql (DemoEndToEndTest checks it). */
    public static final Map<String, Long> STARTING_BALANCES = startingBalances();

    private static Map<String, Long> startingBalances() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("ravi@banka", 5_000_00L);
        m.put("meena@banka", 2_000_00L);
        m.put("anu@bankb", 1_000_00L);
        m.put("kiran@bankb", 3_000_00L);
        m.put("kumar@bankc", 4_000_00L);
        m.put("priya@bankc", 2_500_00L);
        return Map.copyOf(m);
    }

    /** One payment the reseed made. */
    public record Payment(String txnId, String from, String to, long amount, String state, String reason) {
    }

    /** Balances before and after, and every payment made. */
    public record Result(Map<String, Long> before, List<Payment> payments, Map<String, Long> after) {
    }

    private final PaymentOrchestrator orchestrator;
    private final DemoBankAdmin banks;
    private final DemoLock lock;

    public DemoReseedService(PaymentOrchestrator orchestrator, DemoBankAdmin banks, DemoLock lock) {
        this.orchestrator = orchestrator;
        this.banks = banks;
        this.lock = lock;
    }

    /** @throws DemoLock.DemoBusyException if a scenario is running */
    public Result reseed() {
        lock.acquire("reseed");
        try {
            Map<String, Long> before = balances();
            List<Payment> made = new ArrayList<>();
            for (ReseedPlan.Transfer t : ReseedPlan.plan(before, STARTING_BALANCES)) {
                String txnId = "TXN" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
                RespPay r = orchestrator.pay(new ReqPay(txnId, t.from(), t.to(), t.amount()));
                made.add(new Payment(txnId, t.from(), t.to(), t.amount(), r.state().name(), r.reason()));
            }
            Map<String, Long> after = balances();
            log.info("Demo reseed: {} payments; before {} after {}", made.size(), before, after);
            return new Result(before, made, after);
        } finally {
            lock.release();
        }
    }

    /** "-" switches it off (the load test does). */
    @Scheduled(cron = "${switch.demo.reseed-cron:0 30 3 * * *}", zone = "${switch.demo.reseed-zone:Asia/Kolkata}")
    public void nightly() {
        try {
            reseed();
        } catch (DemoLock.DemoBusyException e) {
            log.warn("Nightly demo reseed skipped: {}", e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Nightly demo reseed failed: {}", e.toString());
        }
    }

    private Map<String, Long> balances() {
        Map<String, Long> now = new LinkedHashMap<>();
        for (String vpa : STARTING_BALANCES.keySet().stream().sorted().toList()) {
            now.put(vpa, banks.balance(vpa));
        }
        return now;
    }
}

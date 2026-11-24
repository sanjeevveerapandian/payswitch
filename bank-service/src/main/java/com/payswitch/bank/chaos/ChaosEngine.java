package com.payswitch.bank.chaos;

import com.payswitch.bank.metrics.BankMetrics;
import com.payswitch.common.bank.BankResponse;
import com.payswitch.common.bank.DeclineReason;
import com.payswitch.common.bank.OpType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Makes the bank unreliable on purpose. Only exists when the Spring profile "chaos" is active;
 * without it, the bank behaves normally and there is no way to switch chaos on.
 */
@Component
@Profile("chaos")
public class ChaosEngine {

    private static final Logger log = LoggerFactory.getLogger(ChaosEngine.class);

    /** How long a dropped response stays silent. Must be longer than the switch's read timeout. */
    private final Duration dropResponseSleep;
    private final BankMetrics metrics;

    private ChaosSettings settings = ChaosSettings.NONE;
    private Random dice = new Random();
    /** Separate dice for /status-check, so status checks never change what the main dice say. */
    private Random statusDice = new Random();
    /**
     * One-shot failures for the demo's scenarios: op (DEBIT, CREDIT, REVERSE, STATUS_CHECK) -> what
     * to do to the NEXT request for that op. Removed the moment it is used.
     */
    private final Map<String, ChaosDecision> once = new HashMap<>();

    /**
     * Phase 13, the public demo: chaos left on by one visitor must not spoil the demo for the next.
     * This long after the LAST change (settings or a one-shot rule), everything goes back to zero.
     * Zero = never (tests, and the load test, which runs chaos for 20 minutes on purpose).
     */
    private final Duration autoResetAfter;
    private final Clock clock;
    private Instant lastChange;

    /** For unit tests: no auto-reset. */
    public ChaosEngine(Duration dropResponseSleep, BankMetrics metrics) {
        this(dropResponseSleep, Duration.ZERO, metrics, Clock.systemUTC());
    }

    @Autowired
    public ChaosEngine(@Value("${bank.chaos.drop-response-sleep:8s}") Duration dropResponseSleep,
                       @Value("${bank.chaos.auto-reset-after:0s}") Duration autoResetAfter,
                       BankMetrics metrics) {
        this(dropResponseSleep, autoResetAfter, metrics, Clock.systemUTC());
    }

    ChaosEngine(Duration dropResponseSleep, Duration autoResetAfter, BankMetrics metrics, Clock clock) {
        this.dropResponseSleep = dropResponseSleep;
        this.autoResetAfter = autoResetAfter;
        this.metrics = metrics;
        this.clock = clock;
        this.lastChange = clock.instant();
    }

    public synchronized ChaosSettings settings() {
        expireIfStale();
        return settings;
    }

    /**
     * Checked lazily, on every read and every bank call, instead of by a timer: no thread to manage,
     * and nobody can ever observe chaos that should already have been switched off.
     * Callers hold the lock.
     */
    private void expireIfStale() {
        if (autoResetAfter.isZero() || (settings.equals(ChaosSettings.NONE) && once.isEmpty())) {
            return;
        }
        if (!clock.instant().isBefore(lastChange.plus(autoResetAfter))) {
            log.warn("Chaos auto-reset: nothing changed for {}, back to no chaos (was {}, one-shot {})",
                    autoResetAfter, settings, once);
            settings = ChaosSettings.NONE;
            dice = new Random();
            statusDice = new Random();
            once.clear();
        }
    }

    /** New settings. A seed also restarts the dice, so the next decisions repeat exactly. */
    public synchronized void configure(ChaosSettings newSettings) {
        this.settings = newSettings;
        this.dice = newSettings.seed() == null ? new Random() : new Random(newSettings.seed());
        this.statusDice = newSettings.seed() == null ? new Random() : new Random(newSettings.seed() + 1);
        this.once.clear();                              // "reset all chaos" really resets everything
        this.lastChange = clock.instant();
        log.warn("Chaos settings changed: {}", newSettings);
    }

    /**
     * Arms a one-shot failure: the NEXT request for {@code op} gets {@code mode}, then it is gone.
     *
     * @param op   DEBIT, CREDIT, REVERSE or STATUS_CHECK
     * @param mode decline, error or drop_response (STATUS_CHECK: error only)
     */
    public synchronized void armOnce(String op, String mode) {
        ChaosDecision decision = switch (mode == null ? "" : mode.toLowerCase(java.util.Locale.ROOT)) {
            case "decline" -> ChaosDecision.DECLINE;
            case "error" -> ChaosDecision.ERROR;
            case "drop_response" -> ChaosDecision.DROP_RESPONSE;
            default -> throw new IllegalArgumentException("mode must be decline, error or drop_response");
        };
        String key = op == null ? "" : op.toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("DEBIT", "CREDIT", "REVERSE", "STATUS_CHECK").contains(key)) {
            throw new IllegalArgumentException("op must be DEBIT, CREDIT, REVERSE or STATUS_CHECK");
        }
        if (key.equals("STATUS_CHECK") && decision != ChaosDecision.ERROR) {
            throw new IllegalArgumentException("a status check can only be given mode error");
        }
        expireIfStale();                                // an old leftover must not get a fresh 5 minutes
        once.put(key, decision);
        lastChange = clock.instant();
        log.warn("Chaos armed ONCE: the next {} gets {}", key, decision);
    }

    /**
     * Takes back a one-shot rule that no request used (a demo scenario cleaning up after itself).
     *
     * @return true if a rule was still waiting
     */
    public synchronized boolean disarmOnce(String op) {
        boolean removed = once.remove(op == null ? "" : op.toUpperCase(java.util.Locale.ROOT)) != null;
        if (removed) {
            log.info("Chaos one-shot rule for {} taken back unused", op);
        }
        return removed;
    }

    /** The one-shot failures still waiting for their request. */
    public synchronized Map<String, ChaosDecision> pendingOnce() {
        expireIfStale();
        return Map.copyOf(once);
    }

    /**
     * Runs one POST /status-check with chaos applied. Only statusErrorRate applies here.
     *
     * @throws ChaosFailureException with probability statusErrorRate; nothing is looked up or written
     */
    public <T> T runStatusCheck(String txnId, Supplier<T> check) {
        boolean fail;
        synchronized (this) {
            expireIfStale();
            fail = statusDice.nextDouble() < settings.statusErrorRate();
            fail |= once.remove("STATUS_CHECK") != null;   // the roll still happens: seeds stay repeatable
        }
        if (fail) {
            metrics.chaosInjected("status_error");
            log.warn("CHAOS STATUS-CHECK {}: answering HTTP 500, nothing looked up", txnId);
            throw new ChaosFailureException("STATUS-CHECK " + txnId);
        }
        return check.get();
    }

    /**
     * Pure rule: which decision a given roll of the dice means. Exactly one roll per call,
     * whatever the settings, so a seed always produces the same sequence of rolls.
     *
     * @param roll a number in [0, 1)
     */
    public static ChaosDecision decide(ChaosSettings s, double roll) {
        if (roll < s.declineRate()) {
            return ChaosDecision.DECLINE;
        }
        if (roll < s.declineRate() + s.errorRate()) {
            return ChaosDecision.ERROR;
        }
        if (roll < s.declineRate() + s.errorRate() + s.dropResponseRate()) {
            return ChaosDecision.DROP_RESPONSE;
        }
        return ChaosDecision.PROCEED;
    }

    /**
     * Runs one bank operation with chaos applied.
     *
     * @param operation the real work. It MUST be a call into a @Transactional service method, so
     *                  that by the time it returns here, its database transaction is committed.
     */
    public BankResponse run(OpType op, String txnId, Supplier<BankResponse> operation) {
        ChaosSettings s;
        ChaosDecision decision;
        synchronized (this) {
            expireIfStale();
            s = settings;
            decision = decide(s, dice.nextDouble());       // always roll, so a seed stays repeatable
            ChaosDecision forced = once.remove(op.name());
            if (forced != null) {
                decision = forced;
                log.warn("CHAOS ONCE {} {}: {}", op, txnId, forced);
            }
        }

        long delay = delayFor(s);
        if (delay > 0) {
            metrics.chaosInjected("delay");
            sleep(Duration.ofMillis(delay));
        }

        switch (decision) {
            case DECLINE -> {
                metrics.chaosInjected("decline");
                log.warn("CHAOS {} {}: answering DECLINED, nothing processed", op, txnId);
                return BankResponse.declined(txnId, op, DeclineReason.SIMULATED_DECLINE,
                        "Simulated decline (chaos)");
            }
            case ERROR -> {
                metrics.chaosInjected("error");
                log.warn("CHAOS {} {}: answering HTTP 500, nothing processed", op, txnId);
                throw new ChaosFailureException(op + " " + txnId);
            }
            case DROP_RESPONSE -> {
                metrics.chaosInjected("drop_response");
                BankResponse response = operation.get();
                // operation.get() went through a @Transactional proxy. A Spring proxy commits
                // BEFORE it returns to its caller, and if the commit fails it throws instead of
                // returning. So reaching this line means the change is already saved for good.
                // Belt and braces: if someone ever wraps this in an outer transaction, the commit
                // would be delayed until AFTER our sleep. Refuse loudly instead.
                if (TransactionSynchronizationManager.isActualTransactionActive()) {
                    throw new IllegalStateException("drop-response chaos must run outside any transaction");
                }
                log.warn("CHAOS {} {}: committed with result {}, now silent for {}",
                        op, txnId, response.result(), dropResponseSleep);
                sleep(dropResponseSleep);
                return response;   // nobody is listening any more; Tomcat may fail to write it
            }
            default -> {
                return operation.get();
            }
        }
    }

    /**
     * delayMs plus a random 0..delayJitterMs. The random part comes from ThreadLocalRandom, NOT from
     * the seeded dice: a seed must keep producing the same decisions whatever the delays are.
     */
    static long delayFor(ChaosSettings s) {
        long jitter = s.delayJitterMs() > 0 ? ThreadLocalRandom.current().nextLong(s.delayJitterMs() + 1) : 0;
        return s.delayMs() + jitter;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

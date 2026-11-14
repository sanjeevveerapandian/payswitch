package com.payswitch.bank.chaos;

import com.payswitch.common.bank.BankResponse;
import com.payswitch.common.bank.DeclineReason;
import com.payswitch.common.bank.OpType;
import java.time.Duration;
import java.util.Random;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private ChaosSettings settings = ChaosSettings.NONE;
    private Random dice = new Random();

    public ChaosEngine(@Value("${bank.chaos.drop-response-sleep:8s}") Duration dropResponseSleep) {
        this.dropResponseSleep = dropResponseSleep;
    }

    public synchronized ChaosSettings settings() {
        return settings;
    }

    /** New settings. A seed also restarts the dice, so the next decisions repeat exactly. */
    public synchronized void configure(ChaosSettings newSettings) {
        this.settings = newSettings;
        this.dice = newSettings.seed() == null ? new Random() : new Random(newSettings.seed());
        log.warn("Chaos settings changed: {}", newSettings);
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
            s = settings;
            decision = decide(s, dice.nextDouble());
        }

        if (s.delayMs() > 0) {
            sleep(Duration.ofMillis(s.delayMs()));
        }

        switch (decision) {
            case DECLINE -> {
                log.warn("CHAOS {} {}: answering DECLINED, nothing processed", op, txnId);
                return BankResponse.declined(txnId, op, DeclineReason.SIMULATED_DECLINE,
                        "Simulated decline (chaos)");
            }
            case ERROR -> {
                log.warn("CHAOS {} {}: answering HTTP 500, nothing processed", op, txnId);
                throw new ChaosFailureException(op + " " + txnId);
            }
            case DROP_RESPONSE -> {
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

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

package com.payswitch.switchservice.demo;

import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The "switch-crash" scenario: kill this process at the worst moment of a payment, on purpose.
 *
 * <p>The worst moment is right after the payer's bank said "debited" but BEFORE the switch has saved
 * DEBITED: the bank has taken Ravi's money, and the switch's own record still says DEBIT_SENT. After
 * Docker restarts the switch, startup recovery must find that payment, ask Bank A what happened, and
 * finish it. If it did not, Ravi's money would sit in transit forever.
 *
 * <p>Off unless switch.demo.enabled=true, and even then only armed for ONE payment by the scenario:
 * the scenario's own txnId (Phase 13). On a public demo, a stranger's payment that happens to be
 * debited at the same moment must never be the one that kills the switch.
 */
@Component
public class DemoCrash {

    private static final Logger log = LoggerFactory.getLogger(DemoCrash.class);

    private final boolean enabled;
    /** The txnId that kills the switch, or null. */
    private final AtomicReference<String> armedFor = new AtomicReference<>();

    public DemoCrash(@Value("${switch.demo.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    /** When THIS payment reaches "debit confirmed, not yet saved", the switch dies. */
    public void arm(String txnId) {
        if (!enabled) {
            throw new IllegalStateException("demo scenarios are disabled (switch.demo.enabled=false)");
        }
        armedFor.set(txnId);
    }

    /** The scenario's payment ended without reaching the crash point (e.g. declined): stand down. */
    public void disarm(String txnId) {
        String armed = armedFor.get();
        // compareAndSet compares with ==, so hand it the very object stored; equals() decides.
        if (armed != null && armed.equals(txnId)) {
            armedFor.compareAndSet(armed, null);
        }
    }

    /** Called by PaymentFlow after a successful debit, before DEBITED is saved. */
    public void afterDebitBeforeSave(String txnId) {
        String armed = armedFor.get();
        // The txnId here was read back from the database: an equal String, not the same object.
        if (enabled && armed != null && armed.equals(txnId) && armedFor.compareAndSet(armed, null)) {
            log.error("DEMO CRASH: txn {} was debited at the payer's bank; killing the switch BEFORE saving "
                    + "DEBITED. Docker restarts it; startup recovery must finish this payment.", txnId);
            halt();
        }
    }

    /**
     * Like kill -9: no shutdown hooks, no graceful shutdown, no last writes. Public so a test can
     * replace it; nothing else may call it.
     */
    public void halt() {
        Runtime.getRuntime().halt(137);
    }
}

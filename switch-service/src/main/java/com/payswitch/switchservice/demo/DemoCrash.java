package com.payswitch.switchservice.demo;

import java.util.concurrent.atomic.AtomicBoolean;
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
 * <p>Off unless switch.demo.enabled=true, and even then only armed for ONE payment by the scenario.
 */
@Component
public class DemoCrash {

    private static final Logger log = LoggerFactory.getLogger(DemoCrash.class);

    private final boolean enabled;
    private final AtomicBoolean armed = new AtomicBoolean();

    public DemoCrash(@Value("${switch.demo.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    /** The next payment that reaches "debit confirmed, not yet saved" kills the switch. */
    public void arm() {
        if (!enabled) {
            throw new IllegalStateException("demo scenarios are disabled (switch.demo.enabled=false)");
        }
        armed.set(true);
    }

    /** Called by PaymentFlow after a successful debit, before DEBITED is saved. */
    public void afterDebitBeforeSave(String txnId) {
        if (enabled && armed.compareAndSet(true, false)) {
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

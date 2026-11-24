package com.payswitch.switchservice.demo;

import java.util.concurrent.Semaphore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Phase 13: the public demo runs ONE demo action at a time (a scenario or a reseed).
 *
 * <p>Why: scenarios change the banks' chaos settings. Two at once would undo each other's settings
 * (scenario A restores Bank B's chaos while scenario B still needs it), and each visitor would see
 * the other's failure instead of their own.
 *
 * <p>A Semaphore, not a lock: a lock must be released by the thread that took it, but the
 * switch-crash scenario answers the browser at once and releases from its background thread.
 * One switch instance only: several would need a shared lock (e.g. a Postgres advisory lock).
 */
@Component
@ConditionalOnProperty(name = "switch.demo.enabled", havingValue = "true")
public class DemoLock {

    private final Semaphore permit = new Semaphore(1);
    private volatile String holder;

    /** @throws DemoBusyException if another demo action is running; never waits */
    public void acquire(String what) {
        if (!permit.tryAcquire()) {
            throw new DemoBusyException(holder);
        }
        holder = what;
    }

    /** Exactly once per successful acquire. */
    public void release() {
        holder = null;
        permit.release();
    }

    /** Answered 429 with Retry-After: the browser shows "busy, try again in a few seconds". */
    public static class DemoBusyException extends RuntimeException {
        public DemoBusyException(String running) {
            super("Busy: " + (running == null ? "another scenario" : "the \"" + running + "\" scenario")
                    + " is running. Try again in a few seconds.");
        }
    }
}

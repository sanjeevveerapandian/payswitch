package com.payswitch.observability;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs something only once the current database transaction has COMMITTED.
 *
 * <p>Used for metrics: "payment finished as SUCCESS" must not be counted if the transaction that
 * saved SUCCESS then rolls back. With no transaction active, it simply runs now.
 */
public final class AfterCommit {

    private AfterCommit() {
    }

    public static void run(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}

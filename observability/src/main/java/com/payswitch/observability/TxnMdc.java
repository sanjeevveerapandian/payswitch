package com.payswitch.observability;

import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * The payment's id (and, where it helps, its state) in the logging context of the current thread.
 *
 * <p>MDC ("mapped diagnostic context") is a per-thread map that the logger copies into EVERY log
 * line written on that thread. Put txnId in once at the start of the work, and every line logged
 * while working on that payment carries it, without passing it to each log call.
 *
 * <p>The danger is a thread pool: the thread is reused for the next request, so whatever we put in
 * MUST come out again. Hence {@link #txn} returns a {@link Scope} for try-with-resources, which puts
 * the previous values back on close, even if the work threw.
 */
public final class TxnMdc {

    /** The HTTP header that carries the txnId from PSP to switch to bank. */
    public static final String HEADER = "X-Txn-Id";
    public static final String TXN_ID = "txnId";
    public static final String STATE = "state";

    /** Same shape our txnIds have. Anything else in the header is ignored, never logged. */
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private TxnMdc() {
    }

    /** True for a value that is safe to put in logs and headers (no spaces, quotes or newlines). */
    public static boolean isValid(String txnId) {
        return txnId != null && VALID.matcher(txnId).matches();
    }

    /**
     * Sets txnId for the current thread until the returned scope is closed.
     * An invalid id sets nothing (the scope still restores correctly).
     */
    public static Scope txn(String txnId) {
        Scope scope = new Scope(MDC.get(TXN_ID), MDC.get(STATE));
        if (isValid(txnId)) {
            if (!txnId.equals(scope.previousTxnId)) {
                MDC.remove(STATE);                       // the old state belonged to another payment
            }
            MDC.put(TXN_ID, txnId);
        }
        return scope;
    }

    /** Records the payment's current state, but only while we are inside that payment's scope. */
    public static void state(String txnId, String state) {
        if (txnId != null && txnId.equals(MDC.get(TXN_ID)) && state != null) {
            MDC.put(STATE, state);
        }
    }

    /** The txnId of the payment this thread is working on, or null. */
    public static String currentTxnId() {
        return MDC.get(TXN_ID);
    }

    /** Puts the previous txnId and state back. Never throws. */
    public static final class Scope implements AutoCloseable {

        private final String previousTxnId;
        private final String previousState;

        private Scope(String previousTxnId, String previousState) {
            this.previousTxnId = previousTxnId;
            this.previousState = previousState;
        }

        @Override
        public void close() {
            restore(TXN_ID, previousTxnId);
            restore(STATE, previousState);
        }

        private static void restore(String key, String value) {
            if (value == null) {
                MDC.remove(key);
            } else {
                MDC.put(key, value);
            }
        }
    }
}

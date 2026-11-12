package com.payswitch.psp;

/**
 * The switch did not give us an answer (down, timeout, or a 5xx). We do NOT know whether the
 * payment happened. The txnId is kept so the caller can ask again with GET /pay/{txnId}.
 */
public class SwitchUnavailableException extends RuntimeException {

    private final String txnId;

    public SwitchUnavailableException(String txnId, Throwable cause) {
        super("The switch did not answer. Payment status is unknown; check GET /pay/" + txnId, cause);
        this.txnId = txnId;
    }

    public String txnId() {
        return txnId;
    }
}

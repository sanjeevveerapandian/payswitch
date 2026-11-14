package com.payswitch.bank.chaos;

/** Chaos chose ERROR: the API answers HTTP 500. Nothing was written. */
public class ChaosFailureException extends RuntimeException {

    public ChaosFailureException(String what) {
        super("Simulated failure (chaos) for " + what + ". Nothing was processed.");
    }
}

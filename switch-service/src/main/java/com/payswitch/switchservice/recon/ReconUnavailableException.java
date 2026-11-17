package com.payswitch.switchservice.recon;

/** A bank's records could not be read completely, so this run decides nothing. Try again later. */
public class ReconUnavailableException extends RuntimeException {

    public ReconUnavailableException(String message) {
        super(message);
    }
}

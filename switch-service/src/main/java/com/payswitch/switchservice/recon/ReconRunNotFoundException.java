package com.payswitch.switchservice.recon;

public class ReconRunNotFoundException extends RuntimeException {

    public ReconRunNotFoundException(long runId) {
        super("No recon run with id " + runId);
    }
}

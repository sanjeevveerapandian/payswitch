package com.payswitch.switchservice.settlement;

public class SettlementCycleNotFoundException extends RuntimeException {

    public SettlementCycleNotFoundException(long cycleId) {
        super("No settlement cycle " + cycleId);
    }
}

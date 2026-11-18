package com.payswitch.bank.settlement;

/** The same cycle was advised twice with different amounts. Nothing was changed by the second one. */
public class SettlementAdviceConflictException extends RuntimeException {

    public SettlementAdviceConflictException(long cycleId, long applied, long asked) {
        super("Settlement cycle " + cycleId + " was already applied with net " + applied
                + " paise; refusing a second advice with net " + asked);
    }
}

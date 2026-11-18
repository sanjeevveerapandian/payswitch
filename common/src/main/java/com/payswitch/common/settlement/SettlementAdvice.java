package com.payswitch.common.settlement;

/**
 * The switch telling one bank the result of a settlement cycle.
 *
 * @param cycleId   which cycle; the bank applies each cycle at most once
 * @param netAmount this bank's net position in paise: what it RECEIVES minus what it PAYS.
 *                  Negative = the bank paid out more than it took in (its reserve goes down).
 */
public record SettlementAdvice(long cycleId, long netAmount) {

    public SettlementAdvice {
        if (cycleId < 1) {
            throw new IllegalArgumentException("cycleId must be positive");
        }
    }
}

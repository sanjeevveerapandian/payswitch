package com.payswitch.common.settlement;

/**
 * The bank's answer: "cycle {@code cycleId} for {@code netAmount} is in my books". The same answer
 * comes back however many times the same advice is sent.
 */
public record SettlementAdviceResponse(long cycleId, long netAmount) {
}

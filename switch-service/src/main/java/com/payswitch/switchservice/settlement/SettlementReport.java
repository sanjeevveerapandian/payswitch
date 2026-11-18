package com.payswitch.switchservice.settlement;

import java.time.Instant;
import java.util.List;

/**
 * GET /admin/settlement/cycles/{id}.
 *
 * @param netPositionsSum must be 0 for every cycle that is not FAILED
 */
public record SettlementReport(long cycleId, String status, Instant openedAt, Instant closedAt, Instant settledAt,
                               String failureReason, List<SettlementItem> items, List<Position> netPositions,
                               long netPositionsSum, GrossVsNet grossVsNet) {

    /**
     * @param netAmount receives minus pays, in paise
     * @param advised   the bank has confirmed this amount is in its books
     */
    public record Position(String bankCode, long netAmount, boolean advised) {
    }
}

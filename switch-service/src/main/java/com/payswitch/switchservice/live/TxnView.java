package com.payswitch.switchservice.live;

import java.time.Instant;
import java.util.List;

/**
 * One payment as the live monitor shows it: who, how much, where it is now, and how it got there.
 *
 * @param amount paise
 * @param path   every state it has been in, oldest first (INITIATED, DEBIT_SENT, ...)
 */
public record TxnView(String txnId, String payerVpa, String payeeVpa, long amount, String state, Instant createdAt,
                      List<Step> path) {

    public record Step(String state, String reason, Instant at) {
    }
}

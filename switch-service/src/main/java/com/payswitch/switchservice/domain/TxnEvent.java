package com.payswitch.switchservice.domain;

import java.time.Instant;

/**
 * One row of txn_events: a single state change that happened, and why. Never updated or deleted.
 *
 * @param fromState null for the very first event (the payment being created)
 */
public record TxnEvent(String txnId, TxnState fromState, TxnState toState, String reason, Instant createdAt) {
}

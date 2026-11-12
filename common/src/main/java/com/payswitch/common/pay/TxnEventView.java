package com.payswitch.common.pay;

import java.time.Instant;

/** One step in a payment's history. fromState is null for the first step (creation). */
public record TxnEventView(String fromState, String toState, String reason, Instant at) {
}

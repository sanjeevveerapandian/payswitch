package com.payswitch.switchservice.live;

import java.time.Instant;

/**
 * "Payment X moved from state A to state B." Published by TransactionService on every state change
 * (and on creation, with from = null), and handed to listeners only AFTER the database commit.
 *
 * <p>Carries the VPAs and amount because the demo UI shows them. That is fine for a UI event; it is
 * exactly what must never become a metric label.
 *
 * @param amount paise
 * @param from   null for the creation event
 */
public record TxnStateChanged(String txnId, String payerVpa, String payeeVpa, long amount, String from, String to,
                              String reason, Instant at) {
}

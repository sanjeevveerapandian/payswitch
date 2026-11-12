package com.payswitch.switchservice.domain;

import java.time.Instant;

/**
 * One row of the transactions table: the CURRENT picture of a payment.
 *
 * @param payerBank bank code such as BANKA, or null if the VPA's handle is unknown
 * @param amount    in paise
 * @param version   goes up by 1 on every state change; used for optimistic locking
 */
public record Txn(String txnId, String payerVpa, String payeeVpa, String payerBank, String payeeBank,
                  long amount, TxnState state, long version, Instant createdAt, Instant updatedAt) {
}

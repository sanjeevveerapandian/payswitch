package com.payswitch.common.bank;

import java.time.Instant;

/**
 * One operation a bank did (or refused) for the switch.
 *
 * @param amount    paise that actually moved; 0 when status is not SUCCESS
 * @param createdAt when the bank first received the request, by the bank's own clock
 */
public record ReconEntry(String txnId, OpType op, ReconStatus status, long amount, Instant createdAt) {
}

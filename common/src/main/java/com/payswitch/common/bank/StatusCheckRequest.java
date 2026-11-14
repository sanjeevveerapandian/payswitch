package com.payswitch.common.bank;

import com.payswitch.common.Validation;

/**
 * Switch -> bank: "what happened to (txnId, op)?" Body of POST /status-check.
 *
 * @param fence if the bank has no record of it, also promise never to do it in the future
 *              (write a FENCED tombstone). Not allowed for REVERSE: fencing a refund could leave
 *              the payer's money stuck forever.
 */
public record StatusCheckRequest(String txnId, OpType op, boolean fence) {

    public StatusCheckRequest {
        Validation.requireText(txnId, "txnId");
        if (op == null) {
            throw new IllegalArgumentException("op must not be null");
        }
        if (fence && op == OpType.REVERSE) {
            throw new IllegalArgumentException("A REVERSE cannot be fenced: that would block a refund");
        }
    }
}

package com.payswitch.common.bank;

/** Body of GET /status/{txnId}?op=... */
public record StatusResponse(String txnId, OpType op, TxnStatus status) {
}

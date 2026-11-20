package com.payswitch.load;

/**
 * What happened to one payment, as the payer's app saw it.
 *
 * @param txnId         from the PSP's answer (also present in its 502 answer); null if no answer at all
 * @param httpStatus    the PSP's HTTP status, or -1 if the request itself failed (connection refused...)
 * @param customerState SUCCESS / FAILED / REVERSED / PENDING as the PSP told the customer, or ERROR
 * @param latencyMs     time from sending the request to having the full answer
 */
public record PaymentResult(int index, String txnId, int httpStatus, String customerState, String reason,
                            long latencyMs, boolean overdraftAttempt) {

    public boolean answered() {
        return httpStatus == 200;
    }
}

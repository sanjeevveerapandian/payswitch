package com.payswitch.psp;

/**
 * Body of the PSP's public POST /pay, as the payer's app sends it.
 *
 * @param amount rupees as TEXT, e.g. "200.50". Text, not a JSON number, so no float ever
 *               touches the amount on its way in.
 */
public record PayRequest(String payerVpa, String payeeVpa, String amount) {
}

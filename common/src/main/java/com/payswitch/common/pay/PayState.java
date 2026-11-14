package com.payswitch.common.pay;

/**
 * What the customer is told. The switch has 9 internal states; a person needs only these 4.
 */
public enum PayState {

    /** The payee has the money. */
    SUCCESS,

    /** The payment did not happen; the payer's money never left. */
    FAILED,

    /** The payer was debited, the payment could not complete, and the money was given back. */
    REVERSED,

    /**
     * Not finished, or we do not know yet. Something was sent to a bank and no clear answer came
     * back. Telling the customer SUCCESS or FAILED here would be a guess.
     */
    PENDING;

    /** Maps the switch's internal state name (e.g. "DEBIT_SENT") to what the customer sees. */
    public static PayState fromSwitchState(String switchState) {
        if (switchState == null) {
            throw new IllegalArgumentException("switch state must not be null");
        }
        return switch (switchState) {
            case "SUCCESS" -> SUCCESS;
            case "FAILED" -> FAILED;
            case "REVERSED" -> REVERSED;
            // Not terminal: the story is not over, so the honest answer is "pending".
            case "INITIATED", "DEBIT_SENT", "DEBITED", "CREDIT_SENT", "REVERSAL_SENT", "DEEMED_APPROVED" -> PENDING;
            default -> throw new IllegalArgumentException("Unknown switch state: " + switchState);
        };
    }
}

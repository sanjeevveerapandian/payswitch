package com.payswitch.switchservice.recon;

/** What reconciliation concluded about one txnId. */
public enum ReconCategory {

    /** The switch and the banks agree on where the money is. */
    MATCHED,
    /** The switch says money moved, a bank has no record of it moving. */
    SWITCH_ONLY,
    /** A bank moved money the switch does not know about (or says did not move). */
    BANK_ONLY,
    /** Both sides say money moved, but not the same amount. */
    AMOUNT_MISMATCH,
    /** Was DEEMED_APPROVED; recon found the truth and finished it safely. */
    RESOLVED_DEEMED,
    /** Still DEEMED_APPROVED: the payee's bank could not tell us what happened yet. */
    DEEMED_UNRESOLVED,
    /** Older than the cutoff but still mid-payment (e.g. DEBIT_SENT): the resolver has not finished it. */
    UNFINISHED;

    /** Needs a human. Recon never moves money for these. */
    public boolean isException() {
        return this != MATCHED && this != RESOLVED_DEEMED;
    }
}

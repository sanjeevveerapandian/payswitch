package com.payswitch.common.bank;

/** What a status check found. */
public enum CheckResult {
    /** The bank has a saved answer for this operation; see StatusCheckResponse.saved. */
    FOUND,
    /** No record, and nothing was fenced (fence=false). A late request could still arrive and run. */
    NOT_FOUND,
    /** No record of it ever being done, and now it never will be: a FENCED tombstone is saved. */
    FENCED
}

package com.payswitch.load.audit;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * What the auditor found.
 *
 * @param finalStates       txnId -> the switch's final state (lets the load generator compare with
 *                          what each customer was told)
 * @param stateCounts       final state -> how many payments
 * @param reconCategories   latest recon category -> how many txnIds
 * @param fixes             how many payments needed help, by kind (see MoneyAuditor#fixes)
 * @param settlement        one line about the cycle the auditor closed, or why there was none
 * @param explanations      for every failed check: the first 20 offenders with their full history
 */
public record AuditReport(List<CheckResult> checks, Map<String, String> finalStates, Map<String, Integer> stateCounts,
                          Map<String, Integer> reconCategories, Map<String, Long> fixes, String settlement,
                          List<String> explanations, Duration prepareTime, Duration checkTime) {

    public boolean passed() {
        return checks.stream().allMatch(CheckResult::pass);
    }
}

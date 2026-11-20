package com.payswitch.load.audit;

import java.util.List;

/**
 * One audit check: PASS or FAIL, a one-line summary, and every offender (a txnId, or for check 5
 * a customer VPA). The report prints the first 20 offenders with their full history.
 */
public record CheckResult(int number, String name, boolean pass, String detail, List<String> offenders) {

    public static CheckResult of(int number, String name, String detail, List<String> offenders) {
        return new CheckResult(number, name, offenders.isEmpty(), detail, List.copyOf(offenders));
    }

    public String line() {
        return String.format("%s  %d) %s: %s", pass ? "PASS" : "FAIL", number, name, detail);
    }
}

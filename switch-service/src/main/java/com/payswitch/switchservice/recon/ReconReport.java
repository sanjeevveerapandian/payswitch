package com.payswitch.switchservice.recon;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Body of POST /admin/recon/run and GET /admin/recon/runs/{id}.
 *
 * @param status     COMPLETED, PARTIAL (some payments were too new; run the window again later),
 *                   or RUNNING (in progress, or crashed and waiting for its lease to run out)
 * @param counts     how many txnIds landed in each category, plus SKIPPED_TOO_NEW
 * @param exceptions everything that needs a human; recon moved no money for any of these
 */
public record ReconReport(long runId, Instant windowFrom, Instant windowTo, String status,
                          Map<String, Integer> counts, List<Item> exceptions) {

    public record Item(String txnId, String category, String switchState, String bankView, String actionTaken) {
    }
}

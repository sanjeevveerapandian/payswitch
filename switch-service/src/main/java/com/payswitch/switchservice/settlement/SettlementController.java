package com.payswitch.switchservice.settlement;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /admin/settlement/close[?cycleId=]     close (the open cycle, or this one) and settle it
 * POST /admin/settlement/cycles/{id}/settle   settle a CLOSED cycle again (e.g. a bank was down)
 * GET  /admin/settlement/cycles/{id}          items, net positions, gross vs net
 * GET  /admin/settlement/central/reserves     each bank's balance at the central bank
 */
@RestController
public class SettlementController {

    private final SettlementService settlement;
    private final CentralLedger central;

    public SettlementController(SettlementService settlement, CentralLedger central) {
        this.settlement = settlement;
        this.central = central;
    }

    @PostMapping("/admin/settlement/close")
    public SettlementReport close(@RequestParam(required = false) Long cycleId) {
        return settlement.closeAndSettle(cycleId);
    }

    @PostMapping("/admin/settlement/cycles/{id}/settle")
    public SettlementReport settle(@PathVariable long id) {
        return settlement.settle(id);
    }

    @GetMapping("/admin/settlement/cycles/{id}")
    public SettlementReport cycle(@PathVariable long id) {
        return settlement.report(id);
    }

    /** The last SETTLED cycle; 404 if nothing has been settled yet. */
    @GetMapping("/admin/settlement/last")
    public ResponseEntity<SettlementReport> last() {
        return ResponseEntity.of(settlement.lastSettled());
    }

    @GetMapping("/admin/settlement/central/reserves")
    public Map<String, Long> reserves() {
        return central.reserves();
    }

    @ExceptionHandler(SettlementCycleNotFoundException.class)
    public ProblemDetail notFound(SettlementCycleNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** e.g. settling a cycle that is still OPEN, or one that FAILED. */
    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail conflict(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}

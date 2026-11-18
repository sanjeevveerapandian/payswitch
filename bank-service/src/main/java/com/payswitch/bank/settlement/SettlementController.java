package com.payswitch.bank.settlement;

import com.payswitch.common.settlement.SettlementAdvice;
import com.payswitch.common.settlement.SettlementAdviceResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** POST /settlement/advice {cycleId, netAmount}. Not wrapped by chaos: chaos is for payments. */
@RestController
public class SettlementController {

    private final SettlementAdviceService advices;

    public SettlementController(SettlementAdviceService advices) {
        this.advices = advices;
    }

    @PostMapping("/settlement/advice")
    public SettlementAdviceResponse advice(@RequestBody SettlementAdvice advice) {
        return advices.apply(advice);
    }

    @ExceptionHandler(SettlementAdviceConflictException.class)
    public ProblemDetail conflict(SettlementAdviceConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}

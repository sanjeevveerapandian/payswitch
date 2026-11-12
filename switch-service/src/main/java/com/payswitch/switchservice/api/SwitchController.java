package com.payswitch.switchservice.api;

import com.payswitch.common.pay.ReqPay;
import com.payswitch.common.pay.RespPay;
import com.payswitch.common.pay.TxnDetails;
import com.payswitch.switchservice.service.PaymentOrchestrator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The switch's API, called by PSPs.
 *
 * <p>POST /pay answers 200 whenever the switch reached a decision, including FAILED and
 * REVERSED: those are normal business outcomes, and the body says which.
 */
@RestController
public class SwitchController {

    private final PaymentOrchestrator orchestrator;

    public SwitchController(PaymentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/pay")
    public RespPay pay(@RequestBody ReqPay request) {
        return orchestrator.pay(request);
    }

    @GetMapping("/txns/{txnId}")
    public TxnDetails txn(@PathVariable String txnId) {
        return orchestrator.details(txnId);
    }
}

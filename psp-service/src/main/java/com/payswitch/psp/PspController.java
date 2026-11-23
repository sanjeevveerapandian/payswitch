package com.payswitch.psp;

import com.payswitch.common.pay.PayState;
import com.payswitch.common.pay.RespPay;
import com.payswitch.common.pay.TxnDetails;
import com.payswitch.observability.TxnMdc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The public API the payer's app talks to. */
@RestController
public class PspController {

    private final PayService payments;
    private final SwitchClient switchClient;

    public PspController(PayService payments, SwitchClient switchClient) {
        this.payments = payments;
        this.switchClient = switchClient;
    }

    /** Ravi taps "Pay Rs 200 to anu@bankb". */
    @PostMapping("/pay")
    public RespPay pay(@RequestBody PayRequest request) {
        return payments.pay(request);
    }

    /**
     * "What happened to my payment?" Asks the switch, which is the source of truth, and turns its
     * internal state into what a customer understands: SUCCESS, FAILED, REVERSED or PENDING.
     */
    @GetMapping("/pay/{txnId}")
    public RespPay status(@PathVariable String txnId) {
        try (TxnMdc.Scope mdc = TxnMdc.txn(txnId)) {
            return statusInScope(txnId);
        }
    }

    private RespPay statusInScope(String txnId) {
        TxnDetails details = switchClient.details(txnId);
        return new RespPay(details.txnId(), PayState.fromSwitchState(details.state()), details.reason());
    }

}

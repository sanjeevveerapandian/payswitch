package com.payswitch.psp;

import com.payswitch.common.pay.ReqPay;
import com.payswitch.common.pay.RespPay;
import com.payswitch.common.pay.TxnDetails;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/** The public API the payer's app talks to. */
@RestController
public class PspController {

    private final SwitchClient switchClient;

    public PspController(SwitchClient switchClient) {
        this.switchClient = switchClient;
    }

    /** Ravi taps "Pay Rs 200 to anu@bankb". */
    @PostMapping("/pay")
    public RespPay pay(@RequestBody PayRequest request) {
        long paise = RupeeAmount.toPaise(request.amount());          // rupees -> paise, here only
        String txnId = newTxnId();
        ReqPay reqPay = new ReqPay(txnId, request.payerVpa(), request.payeeVpa(), paise);
        try {
            return switchClient.pay(reqPay);
        } catch (ResourceAccessException | HttpServerErrorException e) {
            throw new SwitchUnavailableException(txnId, e);
        }
    }

    /** "What happened to my payment?" Asks the switch, which is the source of truth. */
    @GetMapping("/pay/{txnId}")
    public RespPay status(@PathVariable String txnId) {
        TxnDetails details = switchClient.details(txnId);
        return new RespPay(details.txnId(), details.state(), details.reason());
    }

    /**
     * A random 128-bit id: unique without asking a database or another server.
     * 35 characters, e.g. TXN3F2A9C0E5B7D4A1F8E6C2B9D0A7F4E31.
     */
    static String newTxnId() {
        return "TXN" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }
}

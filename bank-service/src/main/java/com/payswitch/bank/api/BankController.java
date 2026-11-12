package com.payswitch.bank.api;

import com.payswitch.bank.service.BankOperationsService;
import com.payswitch.common.bank.BalanceResponse;
import com.payswitch.common.bank.BankResponse;
import com.payswitch.common.bank.CreditRequest;
import com.payswitch.common.bank.DebitRequest;
import com.payswitch.common.bank.OpType;
import com.payswitch.common.bank.ReverseRequest;
import com.payswitch.common.bank.StatusResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin HTTP layer: turn JSON into a request object, call the service, return its answer.
 *
 * <p>DEBIT/CREDIT/REVERSE always answer 200 when the bank made a decision, including DECLINED.
 * The switch reads "result" in the body. 400 means the request itself was malformed; 500 means
 * a real fault, which is safe to retry because nothing was committed.
 */
@RestController
public class BankController {

    private final BankOperationsService bank;

    public BankController(BankOperationsService bank) {
        this.bank = bank;
    }

    @PostMapping("/debit")
    public BankResponse debit(@RequestBody DebitRequest request) {
        return bank.debit(request);
    }

    @PostMapping("/credit")
    public BankResponse credit(@RequestBody CreditRequest request) {
        return bank.credit(request);
    }

    @PostMapping("/reverse")
    public BankResponse reverse(@RequestBody ReverseRequest request) {
        return bank.reverse(request);
    }

    @GetMapping("/status/{txnId}")
    public StatusResponse status(@PathVariable String txnId, @RequestParam OpType op) {
        return bank.status(txnId, op);
    }

    @GetMapping("/accounts/{vpa}/balance")
    public BalanceResponse balance(@PathVariable String vpa) {
        return bank.balance(vpa);
    }
}

package com.payswitch.bank.api;

import com.payswitch.bank.chaos.ChaosEngine;
import com.payswitch.bank.service.BankOperationsService;
import com.payswitch.common.bank.BalanceResponse;
import com.payswitch.common.bank.BankResponse;
import com.payswitch.common.bank.CreditRequest;
import com.payswitch.common.bank.DebitRequest;
import com.payswitch.common.bank.OpType;
import com.payswitch.common.bank.ReverseRequest;
import com.payswitch.common.bank.StatusCheckRequest;
import com.payswitch.common.bank.StatusCheckResponse;
import com.payswitch.common.bank.StatusResponse;
import java.util.Optional;
import java.util.function.Supplier;
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
 *
 * <p>Chaos (profile "chaos" only) wraps the three money operations, and /status-check with its own
 * statusErrorRate knob. GET /status and balance are never wrapped, so they stay reliable.
 */
@RestController
public class BankController {

    private final BankOperationsService bank;
    private final Optional<ChaosEngine> chaos;   // empty unless the "chaos" profile is active

    public BankController(BankOperationsService bank, Optional<ChaosEngine> chaos) {
        this.bank = bank;
        this.chaos = chaos;
    }

    @PostMapping("/debit")
    public BankResponse debit(@RequestBody DebitRequest request) {
        return withChaos(OpType.DEBIT, request.txnId(), () -> bank.debit(request));
    }

    @PostMapping("/credit")
    public BankResponse credit(@RequestBody CreditRequest request) {
        return withChaos(OpType.CREDIT, request.txnId(), () -> bank.credit(request));
    }

    @PostMapping("/reverse")
    public BankResponse reverse(@RequestBody ReverseRequest request) {
        return withChaos(OpType.REVERSE, request.txnId(), () -> bank.reverse(request));
    }

    /** Look up (txnId, op), and optionally fence it. Chaos here is statusErrorRate only. */
    @PostMapping("/status-check")
    public StatusCheckResponse statusCheck(@RequestBody StatusCheckRequest request) {
        return chaos.map(c -> c.runStatusCheck(request.txnId(), () -> bank.statusCheck(request)))
                .orElseGet(() -> bank.statusCheck(request));
    }

    @GetMapping("/status/{txnId}")
    public StatusResponse status(@PathVariable String txnId, @RequestParam OpType op) {
        return bank.status(txnId, op);
    }

    @GetMapping("/accounts/{vpa}/balance")
    public BalanceResponse balance(@PathVariable String vpa) {
        return bank.balance(vpa);
    }

    /**
     * {@code operation} calls the @Transactional service, so each call is a full transaction that
     * has committed by the time it returns. This controller itself is not transactional.
     */
    private BankResponse withChaos(OpType op, String txnId, Supplier<BankResponse> operation) {
        return chaos.map(c -> c.run(op, txnId, operation)).orElseGet(operation);
    }
}

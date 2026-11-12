package com.payswitch.switchservice.service;

import com.payswitch.common.bank.BankResponse;
import com.payswitch.common.bank.BankResult;
import com.payswitch.common.bank.CreditRequest;
import com.payswitch.common.bank.DebitRequest;
import com.payswitch.common.bank.ReverseRequest;
import com.payswitch.common.pay.ReqPay;
import com.payswitch.common.pay.RespPay;
import com.payswitch.common.pay.TxnDetails;
import com.payswitch.common.pay.TxnEventView;
import com.payswitch.switchservice.bank.BankClient;
import com.payswitch.switchservice.domain.Txn;
import com.payswitch.switchservice.domain.TxnEvent;
import com.payswitch.switchservice.domain.TxnState;
import com.payswitch.switchservice.routing.BankRoute;
import com.payswitch.switchservice.routing.BankRouter;
import com.payswitch.switchservice.routing.Vpa;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Runs one payment from start to finish: debit the payer's bank, credit the payee's bank,
 * and if the credit is refused, give the payer's money back.
 *
 * <p>Two rules shape this class:
 * <ol>
 *   <li>The state is saved BEFORE each bank call (DEBIT_SENT, then call /debit). If the switch
 *       crashes mid-call, the saved state says which bank to ask about what.</li>
 *   <li>This class is NOT @Transactional. Each state change is its own short database
 *       transaction (inside TransactionService). No database transaction or lock is ever held
 *       open while we wait for a bank.</li>
 * </ol>
 *
 * <p>Phase 4 handles the happy path and business declines. If a bank call throws (timeout,
 * connection refused), the exception propagates and the payment stays in its *_SENT state,
 * which is the honest answer: outcome unknown. Recovering from that is a later phase.
 */
@Service
public class PaymentOrchestrator {

    private final TransactionService txns;
    private final BankRouter router;
    private final BankClient banks;

    public PaymentOrchestrator(TransactionService txns, BankRouter router, BankClient banks) {
        this.txns = txns;
        this.router = router;
        this.banks = banks;
    }

    public RespPay pay(ReqPay request) {
        Txn txn;
        try {
            txn = txns.create(request.txnId(), request.payerVpa(), request.payeeVpa(), request.amount());
        } catch (DuplicateTransactionException alreadyKnown) {
            // The PSP sent the same txnId again (a retry). Do not run the payment twice:
            // answer with whatever state the first attempt has reached.
            return respond(request.txnId());
        }
        if (txn.state() == TxnState.FAILED) {
            return respond(txn.txnId());                    // unknown bank handle
        }
        BankRoute payerBank = router.route(Vpa.parse(txn.payerVpa())).orElseThrow();
        BankRoute payeeBank = router.route(Vpa.parse(txn.payeeVpa())).orElseThrow();

        // ---- Step 1: debit the payer -------------------------------------------------------
        txn = move(txn, TxnState.DEBIT_SENT, "DEBIT_REQUESTED:" + payerBank.code());
        BankResponse debit = banks.debit(payerBank,
                new DebitRequest(txn.txnId(), txn.payerVpa(), txn.amount()));
        if (debit.result() == BankResult.DECLINED) {
            move(txn, TxnState.FAILED, "DEBIT_DECLINED:" + debit.reason());
            return respond(txn.txnId());                    // money never left the payer
        }
        txn = move(txn, TxnState.DEBITED, "DEBIT_OK");

        // ---- Step 2: credit the payee ------------------------------------------------------
        txn = move(txn, TxnState.CREDIT_SENT, "CREDIT_REQUESTED:" + payeeBank.code());
        BankResponse credit = banks.credit(payeeBank,
                new CreditRequest(txn.txnId(), txn.payeeVpa(), txn.amount()));
        if (credit.result() == BankResult.SUCCESS) {
            move(txn, TxnState.SUCCESS, "CREDIT_OK");
            return respond(txn.txnId());                    // money is with the payee
        }

        // ---- Step 3: credit refused, so send the payer's money back -------------------------
        txn = move(txn, TxnState.REVERSAL_SENT, "CREDIT_DECLINED:" + credit.reason());
        BankResponse reversal = banks.reverse(payerBank, new ReverseRequest(txn.txnId()));
        if (reversal.result() == BankResult.SUCCESS) {
            move(txn, TxnState.REVERSED, "REVERSAL_OK");
        } else {
            // The bank says there was no successful debit to undo, so the payer never paid.
            move(txn, TxnState.FAILED, "REVERSAL_DECLINED:" + reversal.reason());
        }
        return respond(txn.txnId());
    }

    /** Current state + full history, for GET /txns/{txnId}. */
    public TxnDetails details(String txnId) {
        Txn txn = txns.get(txnId);
        List<TxnEvent> events = txns.history(txnId);
        List<TxnEventView> views = events.stream()
                .map(e -> new TxnEventView(e.fromState() == null ? null : e.fromState().name(),
                        e.toState().name(), e.reason(), e.createdAt()))
                .toList();
        String latestReason = events.isEmpty() ? null : events.get(events.size() - 1).reason();
        return new TxnDetails(txn.txnId(), txn.payerVpa(), txn.payeeVpa(), txn.payerBank(), txn.payeeBank(),
                txn.amount(), txn.state().name(), latestReason, views);
    }

    /**
     * One state change, guarded by the version we last saw. If anything else touched this
     * payment since, this throws instead of overwriting it.
     */
    private Txn move(Txn txn, TxnState to, String reason) {
        return txns.transition(txn.txnId(), txn.version(), to, reason);
    }

    private RespPay respond(String txnId) {
        TxnDetails details = details(txnId);
        return new RespPay(details.txnId(), details.state(), details.reason());
    }
}

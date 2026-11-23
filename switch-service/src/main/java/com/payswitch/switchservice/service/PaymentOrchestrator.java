package com.payswitch.switchservice.service;

import com.payswitch.common.pay.PayState;
import com.payswitch.observability.TxnMdc;
import com.payswitch.common.pay.ReqPay;
import com.payswitch.common.pay.RespPay;
import com.payswitch.common.pay.TxnDetails;
import com.payswitch.common.pay.TxnEventView;
import com.payswitch.switchservice.domain.Txn;
import com.payswitch.switchservice.domain.TxnEvent;
import com.payswitch.switchservice.domain.TxnState;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;

/**
 * Handles POST /pay: record the payment, make the first attempt, and if a bank's answer was
 * UNKNOWN, try to resolve it for a short while (inline wait) before answering.
 *
 * <p>If resolution is not finished when the inline wait runs out, the customer gets PENDING and
 * the resolution keeps running in the background. GET /pay/{txnId} shows the latest state.
 */
@Service
public class PaymentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PaymentOrchestrator.class);

    private final TransactionService txns;
    private final PaymentFlow flow;
    private final ResolutionProperties resolution;
    private final AsyncTaskExecutor resolutionExecutor;

    public PaymentOrchestrator(TransactionService txns, PaymentFlow flow, ResolutionProperties resolution,
                               @Qualifier("resolutionExecutor") AsyncTaskExecutor resolutionExecutor) {
        this.txns = txns;
        this.flow = flow;
        this.resolution = resolution;
        this.resolutionExecutor = resolutionExecutor;
    }

    /**
     * The txnId in the body is the truth (the X-Txn-Id header is only for logs), so the MDC is set
     * from the body here: every log line of this payment carries it, even if a caller sent no header.
     */
    public RespPay pay(ReqPay request) {
        try (TxnMdc.Scope mdc = TxnMdc.txn(request.txnId())) {
            return payInScope(request);
        }
    }

    private RespPay payInScope(ReqPay request) {
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

        flow.firstAttempt(txn.txnId());

        if (resolution.enabled() && PaymentFlow.RESOLVABLE.contains(txns.get(txn.txnId()).state())) {
            resolveInline(txn.txnId());
        }
        return respond(txn.txnId());
    }

    /**
     * Resolve on a separate thread and wait at most inline-wait for it. If it is slower, answer
     * PENDING now and let it finish on its own. The lease keeps the background job away meanwhile.
     */
    private void resolveInline(String txnId) {
        txns.lease(txnId, resolution.lease());
        try {
            Future<?> work = resolutionExecutor.submit(() -> flow.resolve(txnId));
            work.get(resolution.inlineWait().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException stillWorking) {
            log.info("Txn {}: not resolved within {}; answering PENDING, resolution continues.",
                    txnId, resolution.inlineWait());
        } catch (RejectedExecutionException busy) {
            log.warn("Txn {}: resolver pool is full; the background job will pick it up.", txnId);
        } catch (ExecutionException e) {
            log.error("Txn {}: inline resolution failed; the background job will retry.", txnId, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    private RespPay respond(String txnId) {
        TxnDetails details = details(txnId);
        return new RespPay(details.txnId(), PayState.fromSwitchState(details.state()), details.reason());
    }
}

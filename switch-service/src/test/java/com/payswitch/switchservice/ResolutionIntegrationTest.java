package com.payswitch.switchservice;

import com.payswitch.common.pay.ReqPay;
import com.payswitch.switchservice.bank.BankClient;
import com.payswitch.switchservice.domain.TxnEvent;
import com.payswitch.switchservice.domain.TxnState;
import com.payswitch.switchservice.service.PaymentFlow;
import com.payswitch.switchservice.service.PaymentOrchestrator;
import com.payswitch.switchservice.service.ResolutionProperties;
import com.payswitch.switchservice.service.ResolverJob;
import com.payswitch.switchservice.service.TransactionService;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Base for Phase 6 switch tests: resolution ON, banks replaced by a mock we script per test.
 * Retries are fast (10 ms, x2, 3 attempts) and the background job never fires on its own
 * (interval 1h): tests call it directly. Subclasses share one Spring context and one database.
 */
@SpringBootTest(properties = {
        "switch.resolution.enabled=true",
        "switch.resolution.inline-wait=2s",
        "switch.resolution.stuck-after=0s",
        "switch.resolution.interval=1h",
        "switch.resolution.batch-size=3",
        "switch.resolution.lease=300ms",
        "switch.resolution.run-on-startup=false",
        "switch.retry.base-delay=10ms",
        "switch.retry.multiplier=2",
        "switch.retry.jitter=0.2",
        "switch.retry.max-attempts=3",
})
@Import(TestcontainersConfig.class)
abstract class ResolutionIntegrationTest {

    @MockitoBean
    protected BankClient banks;

    @Autowired
    protected TransactionService service;

    @Autowired
    protected PaymentOrchestrator orchestrator;

    @Autowired
    protected PaymentFlow flow;

    @Autowired
    protected ResolverJob job;

    @Autowired
    protected ResolutionProperties props;

    protected static ReqPay raviPaysAnu200() {
        return new ReqPay("TXN-" + UUID.randomUUID(), "ravi@banka", "anu@bankb", 20_000);
    }

    /** Every state the payment was in, in order (a note repeats the same state). */
    protected List<TxnState> path(String txnId) {
        return service.history(txnId).stream().map(TxnEvent::toState).toList();
    }

    protected List<String> reasons(String txnId) {
        return service.history(txnId).stream().map(TxnEvent::reason).toList();
    }
}

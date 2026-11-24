package com.payswitch.switchservice.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payswitch.common.bank.OpType;
import com.payswitch.common.pay.ReqPay;
import com.payswitch.switchservice.TestcontainersConfig;
import com.payswitch.switchservice.bank.BankCallResult;
import com.payswitch.switchservice.bank.BankClient;
import com.payswitch.switchservice.domain.TxnState;
import com.payswitch.switchservice.service.PaymentOrchestrator;
import com.payswitch.switchservice.service.ResolverJob;
import com.payswitch.switchservice.service.TransactionService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Phase 12, the switch-crash scenario without killing the test JVM: halt() is replaced by a throw,
 * which stops the payment at exactly the same point (debited at the bank, DEBITED not saved).
 */
@SpringBootTest(properties = {
        "switch.demo.enabled=true",
        "switch.resolution.run-on-startup=false",
        "switch.resolution.interval=1h",
        "switch.resolution.stuck-after=0s",
        "switch.recon.enabled=false",
        "switch.settlement.enabled=false",
})
@Import(TestcontainersConfig.class)
class DemoCrashRecoveryTest {

    /** Stands in for the process dying. */
    static class Halted extends RuntimeException {
    }

    @MockitoBean
    private BankClient banks;

    @MockitoSpyBean
    private DemoCrash crash;

    @Autowired
    private PaymentOrchestrator orchestrator;

    @Autowired
    private TransactionService txns;

    @Autowired
    private ResolverJob resolver;

    @Test
    void theSwitchDiesAfterTheDebitAndRecoveryFinishesThePayment() {
        doThrow(new Halted()).when(crash).halt();
        when(banks.debit(any(), any())).thenReturn(BankCallResult.success());
        when(banks.credit(any(), any())).thenReturn(BankCallResult.success());
        when(banks.statusCheckAndFence(any(), any(), eq(OpType.DEBIT))).thenReturn(BankCallResult.success());
        String txnId = "TXN-" + UUID.randomUUID();
        crash.arm(txnId);

        assertThrows(Halted.class, () -> orchestrator.pay(new ReqPay(txnId, "ravi@banka", "anu@bankb", 10_000)));

        // The bank has the debit; the switch's record still says DEBIT_SENT: exactly the crash state.
        assertEquals(TxnState.DEBIT_SENT, txns.get(txnId).state());

        resolver.runOnce();                            // what the restarted switch's resolver does

        assertEquals(TxnState.SUCCESS, txns.get(txnId).state());
        assertEquals("DEBIT_CONFIRMED_BY_STATUS", txns.history(txnId).get(2).reason());
        verify(crash, times(1)).halt();
    }

    @Test
    void armedOnceMeansOnePaymentOnly() {
        doThrow(new Halted()).when(crash).halt();
        when(banks.debit(any(), any())).thenReturn(BankCallResult.success());
        when(banks.credit(any(), any())).thenReturn(BankCallResult.success());
        String armed = "TXN-" + UUID.randomUUID();
        crash.arm(armed);
        assertThrows(Halted.class, () -> orchestrator.pay(new ReqPay(armed, "ravi@banka", "anu@bankb", 10_000)));

        String next = "TXN-" + UUID.randomUUID();
        orchestrator.pay(new ReqPay(next, "ravi@banka", "anu@bankb", 10_000));

        assertEquals(TxnState.SUCCESS, txns.get(next).state());
    }

    /** Phase 13: on a public demo, a stranger's payment debited at the same moment must not kill the switch. */
    @Test
    void onlyThePaymentItWasArmedForTriggersIt() {
        doThrow(new Halted()).when(crash).halt();
        when(banks.debit(any(), any())).thenReturn(BankCallResult.success());
        when(banks.credit(any(), any())).thenReturn(BankCallResult.success());
        String scenarios = "TXN-" + UUID.randomUUID();
        crash.arm(scenarios);

        String strangers = "TXN-" + UUID.randomUUID();
        orchestrator.pay(new ReqPay(strangers, "kiran@bankb", "ravi@banka", 10_000));
        assertEquals(TxnState.SUCCESS, txns.get(strangers).state());
        verify(crash, times(0)).halt();

        crash.disarm(scenarios);                       // the scenario stands down: nothing is armed any more
        orchestrator.pay(new ReqPay(scenarios, "ravi@banka", "anu@bankb", 10_000));
        assertEquals(TxnState.SUCCESS, txns.get(scenarios).state());
        verify(crash, times(0)).halt();
    }

    @Test
    void withDemoOffItCannotBeArmed() {
        DemoCrash off = new DemoCrash(false);
        assertThrows(IllegalStateException.class, () -> off.arm("TXN-1"));
        off.afterDebitBeforeSave("TXN-1");             // never halts
    }
}

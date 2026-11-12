package com.payswitch.switchservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.payswitch.common.bank.BankTxnRequest;
import com.payswitch.switchservice.domain.Txn;
import com.payswitch.switchservice.domain.TxnEvent;
import com.payswitch.switchservice.domain.TxnState;
import com.payswitch.switchservice.routing.BankRoute;
import com.payswitch.switchservice.routing.BankRouter;
import com.payswitch.switchservice.routing.Vpa;
import com.payswitch.switchservice.service.DuplicateTransactionException;
import com.payswitch.switchservice.service.TransactionNotFoundException;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CreateAndRoutingTest extends SwitchIntegrationTest {

    @Autowired
    private BankRouter router;

    @Test
    void routingTableIsLoadedFromApplicationYml() {
        BankRoute a = router.route(Vpa.parse("ravi@banka")).orElseThrow();
        BankRoute b = router.route(Vpa.parse("anu@bankb")).orElseThrow();

        assertEquals(new BankRoute("BANKA", URI.create("http://localhost:8081")), a);
        assertEquals(new BankRoute("BANKB", URI.create("http://localhost:8082")), b);
    }

    @Test
    void createRecordsThePaymentAsInitiatedWithBothBanksResolved() {
        String txnId = newTxnId();

        Txn txn = service.create(txnId, "ravi@banka", "anu@bankb", 20_000);

        assertEquals(txnId, txn.txnId());
        assertEquals("ravi@banka", txn.payerVpa());
        assertEquals("anu@bankb", txn.payeeVpa());
        assertEquals("BANKA", txn.payerBank());
        assertEquals("BANKB", txn.payeeBank());
        assertEquals(20_000, txn.amount());
        assertEquals(TxnState.INITIATED, txn.state());
        assertEquals(0, txn.version());
        List<TxnEvent> events = service.history(txnId);
        assertEquals(1, events.size());
        assertNull(events.get(0).fromState());
        assertEquals(TxnState.INITIATED, events.get(0).toState());
    }

    @Test
    void routingWorksInBothDirections() {
        Txn txn = service.create(newTxnId(), "anu@bankb", "ravi@banka", 5_000);

        assertEquals("BANKB", txn.payerBank());
        assertEquals("BANKA", txn.payeeBank());
        assertEquals(TxnState.INITIATED, txn.state());
    }

    @Test
    void unknownPayeeHandleIsRecordedThenFailed() {
        String txnId = newTxnId();

        Txn txn = service.create(txnId, "ravi@banka", "anu@nobank", 20_000);

        assertEquals(TxnState.FAILED, txn.state());
        assertEquals("BANKA", txn.payerBank());
        assertNull(txn.payeeBank());
        List<TxnEvent> events = service.history(txnId);
        assertEquals(2, events.size());                 // created, then failed
        assertEquals(TxnState.INITIATED, events.get(1).fromState());
        assertEquals(TxnState.FAILED, events.get(1).toState());
        assertEquals("UNKNOWN_HANDLE", events.get(1).reason());
    }

    @Test
    void unknownPayerHandleIsRecordedThenFailed() {
        Txn txn = service.create(newTxnId(), "ravi@nobank", "anu@bankb", 20_000);

        assertEquals(TxnState.FAILED, txn.state());
        assertNull(txn.payerBank());
        assertEquals("BANKB", txn.payeeBank());
    }

    @Test
    void malformedVpaIsRejectedAndNothingIsSaved() {
        String txnId = newTxnId();

        assertThrows(IllegalArgumentException.class, () -> service.create(txnId, "ravi", "anu@bankb", 20_000));
        assertThrows(IllegalArgumentException.class, () -> service.create(txnId, "ravi@banka", "@bankb", 20_000));

        assertThrows(TransactionNotFoundException.class, () -> service.get(txnId));
    }

    @Test
    void badAmountOrPayingYourselfIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.create(newTxnId(), "ravi@banka", "anu@bankb", 0));
        assertThrows(IllegalArgumentException.class,
                () -> service.create(newTxnId(), "ravi@banka", "anu@bankb", BankTxnRequest.MAX_AMOUNT_PAISE + 1));
        assertThrows(IllegalArgumentException.class,
                () -> service.create(newTxnId(), "ravi@banka", "RAVI@banka", 20_000));
    }

    @Test
    void sameTxnIdTwiceIsRejectedAndTheFirstIsUntouched() {
        String txnId = newTxnId();
        service.create(txnId, "ravi@banka", "anu@bankb", 20_000);

        assertThrows(DuplicateTransactionException.class,
                () -> service.create(txnId, "ravi@banka", "anu@bankb", 99_000));

        assertEquals(20_000, service.get(txnId).amount());
        assertEquals(1, service.history(txnId).size());
    }
}

package com.payswitch.switchservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import com.payswitch.switchservice.db.TxnEventRepository;
import com.payswitch.switchservice.domain.Txn;
import com.payswitch.switchservice.domain.TxnState;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Proves "state change + audit event are ONE database transaction": we make the event insert
 * blow up AFTER the state was updated, and check the state update was rolled back too.
 */
class StateAndEventAtomicityTest extends SwitchIntegrationTest {

    @MockitoSpyBean
    private TxnEventRepository events;

    @Test
    void ifTheEventCannotBeWrittenTheStateChangeIsRolledBack() {
        Txn txn = raviPaysAnu();
        doThrow(new IllegalStateException("disk full (simulated)"))
                .when(events).insert(eq(txn.txnId()), eq(TxnState.INITIATED), eq(TxnState.DEBIT_SENT), any());

        assertThrows(IllegalStateException.class,
                () -> service.transition(txn.txnId(), TxnState.DEBIT_SENT, "debit request sent"));

        Txn after = service.get(txn.txnId());
        assertEquals(TxnState.INITIATED, after.state());   // NOT DEBIT_SENT
        assertEquals(0, after.version());
        assertEquals(1, service.history(txn.txnId()).size());
    }
}

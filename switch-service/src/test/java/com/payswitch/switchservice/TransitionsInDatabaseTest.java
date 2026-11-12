package com.payswitch.switchservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.payswitch.switchservice.domain.IllegalStateTransitionException;
import com.payswitch.switchservice.domain.Txn;
import com.payswitch.switchservice.domain.TxnEvent;
import com.payswitch.switchservice.domain.TxnState;
import com.payswitch.switchservice.domain.TxnStateMachine;
import com.payswitch.switchservice.service.TransactionNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/** The state machine enforced end to end: service + real Postgres. */
class TransitionsInDatabaseTest extends SwitchIntegrationTest {

    static Stream<Arguments> allPairs() {
        List<Arguments> pairs = new ArrayList<>();
        for (TxnState from : TxnState.values()) {
            for (TxnState to : TxnState.values()) {
                pairs.add(Arguments.of(from, to));
            }
        }
        return pairs.stream();
    }

    /**
     * For all 81 (from, to) pairs: put a real payment into "from", then try to move it to "to".
     * Allowed -> state changes and exactly one new event. Illegal -> exception and NOTHING changes.
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allPairs")
    void everyPairEitherMovesOrThrowsAndChangesNothing(TxnState from, TxnState to) {
        Txn before = paymentIn(from);
        List<TxnEvent> eventsBefore = service.history(before.txnId());

        if (TxnStateMachine.canTransition(from, to)) {
            Txn after = service.transition(before.txnId(), to, "bank said so");

            assertEquals(to, after.state());
            assertEquals(before.version() + 1, after.version());
            List<TxnEvent> events = service.history(before.txnId());
            assertEquals(eventsBefore.size() + 1, events.size());
            TxnEvent last = events.get(events.size() - 1);
            assertEquals(from, last.fromState());
            assertEquals(to, last.toState());
            assertEquals("bank said so", last.reason());
        } else {
            assertThrows(IllegalStateTransitionException.class,
                    () -> service.transition(before.txnId(), to, "should not happen"));

            Txn after = service.get(before.txnId());
            assertEquals(from, after.state());
            assertEquals(before.version(), after.version());
            assertEquals(eventsBefore, service.history(before.txnId()));
        }
    }

    @ParameterizedTest
    @EnumSource(value = TxnState.class, names = {"SUCCESS", "FAILED", "REVERSED"})
    void nothingCanLeaveATerminalState(TxnState terminal) {
        Txn done = paymentIn(terminal);

        for (TxnState to : TxnState.values()) {
            assertThrows(IllegalStateTransitionException.class,
                    () -> service.transition(done.txnId(), to, "too late"));
        }
        assertEquals(terminal, service.get(done.txnId()).state());
    }

    @Test
    void happyPathWritesOneEventPerStateChangeInOrder() {
        Txn txn = raviPaysAnu();
        service.transition(txn.txnId(), TxnState.DEBIT_SENT, "debit request sent to BANKA");
        service.transition(txn.txnId(), TxnState.DEBITED, "BANKA debited Ravi");
        service.transition(txn.txnId(), TxnState.CREDIT_SENT, "credit request sent to BANKB");
        Txn done = service.transition(txn.txnId(), TxnState.SUCCESS, "BANKB credited Anu");

        List<TxnEvent> events = service.history(txn.txnId());

        assertEquals(5, events.size());                 // 1 creation + 4 moves
        assertNull(events.get(0).fromState());
        assertEquals(TxnState.INITIATED, events.get(0).toState());
        assertEquals("CREATED", events.get(0).reason());
        assertStep(events.get(1), TxnState.INITIATED, TxnState.DEBIT_SENT, "debit request sent to BANKA");
        assertStep(events.get(2), TxnState.DEBIT_SENT, TxnState.DEBITED, "BANKA debited Ravi");
        assertStep(events.get(3), TxnState.DEBITED, TxnState.CREDIT_SENT, "credit request sent to BANKB");
        assertStep(events.get(4), TxnState.CREDIT_SENT, TxnState.SUCCESS, "BANKB credited Anu");

        assertEquals(TxnState.SUCCESS, done.state());
        assertEquals(4, done.version());                // one bump per move
        assertTrue(!done.updatedAt().isBefore(done.createdAt()));
        // Each event starts where the previous one ended: the history is one unbroken chain.
        for (int i = 1; i < events.size(); i++) {
            assertEquals(events.get(i - 1).toState(), events.get(i).fromState());
        }
    }

    @Test
    void transitionOfUnknownTxnIsRejected() {
        assertThrows(TransactionNotFoundException.class,
                () -> service.transition(newTxnId(), TxnState.DEBIT_SENT, "x"));
    }

    @Test
    void blankReasonIsRejectedAndNothingChanges() {
        Txn txn = raviPaysAnu();

        assertThrows(IllegalArgumentException.class,
                () -> service.transition(txn.txnId(), TxnState.DEBIT_SENT, " "));

        assertEquals(TxnState.INITIATED, service.get(txn.txnId()).state());
        assertEquals(1, service.history(txn.txnId()).size());
    }

    private static void assertStep(TxnEvent event, TxnState from, TxnState to, String reason) {
        assertEquals(from, event.fromState());
        assertEquals(to, event.toState());
        assertEquals(reason, event.reason());
    }
}

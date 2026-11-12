package com.payswitch.switchservice.domain;

import static com.payswitch.switchservice.domain.TxnState.CREDIT_SENT;
import static com.payswitch.switchservice.domain.TxnState.DEBITED;
import static com.payswitch.switchservice.domain.TxnState.DEBIT_SENT;
import static com.payswitch.switchservice.domain.TxnState.DEEMED_APPROVED;
import static com.payswitch.switchservice.domain.TxnState.FAILED;
import static com.payswitch.switchservice.domain.TxnState.INITIATED;
import static com.payswitch.switchservice.domain.TxnState.REVERSAL_SENT;
import static com.payswitch.switchservice.domain.TxnState.REVERSED;
import static com.payswitch.switchservice.domain.TxnState.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class TxnStateMachineTest {

    /**
     * The expected rulebook, written out a second time on purpose. If someone edits the table in
     * TxnStateMachine by mistake, this independent copy disagrees and the test fails.
     */
    private static final Set<List<TxnState>> EXPECTED_ALLOWED = Set.of(
            List.of(INITIATED, DEBIT_SENT),
            List.of(INITIATED, FAILED),
            List.of(DEBIT_SENT, DEBITED),
            List.of(DEBIT_SENT, FAILED),
            List.of(DEBIT_SENT, REVERSAL_SENT),
            List.of(DEBITED, CREDIT_SENT),
            List.of(DEBITED, REVERSAL_SENT),
            List.of(CREDIT_SENT, SUCCESS),
            List.of(CREDIT_SENT, REVERSAL_SENT),
            List.of(CREDIT_SENT, DEEMED_APPROVED),
            List.of(DEEMED_APPROVED, SUCCESS),
            List.of(DEEMED_APPROVED, REVERSAL_SENT),
            List.of(REVERSAL_SENT, REVERSED),
            List.of(REVERSAL_SENT, FAILED));

    /** All 9 x 9 = 81 (from, to) pairs. */
    static Stream<Arguments> allPairs() {
        List<Arguments> pairs = new ArrayList<>();
        for (TxnState from : TxnState.values()) {
            for (TxnState to : TxnState.values()) {
                pairs.add(Arguments.of(from, to));
            }
        }
        return pairs.stream();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allPairs")
    void everyPairIsEitherAllowedOrThrows(TxnState from, TxnState to) {
        if (EXPECTED_ALLOWED.contains(List.of(from, to))) {
            assertTrue(TxnStateMachine.canTransition(from, to));
            assertDoesNotThrow(() -> TxnStateMachine.check("T1", from, to));
        } else {
            assertFalse(TxnStateMachine.canTransition(from, to));
            IllegalStateTransitionException e = assertThrows(IllegalStateTransitionException.class,
                    () -> TxnStateMachine.check("T1", from, to));
            assertEquals(from, e.from());
            assertEquals(to, e.to());
        }
    }

    @ParameterizedTest
    @EnumSource(value = TxnState.class, names = {"SUCCESS", "FAILED", "REVERSED"})
    void nothingCanLeaveATerminalState(TxnState terminal) {
        assertTrue(TxnStateMachine.isTerminal(terminal));
        assertTrue(TxnStateMachine.allowedFrom(terminal).isEmpty());
        for (TxnState to : TxnState.values()) {
            assertThrows(IllegalStateTransitionException.class, () -> TxnStateMachine.check("T1", terminal, to));
        }
    }

    @Test
    void exactlyThreeStatesAreTerminal() {
        Set<TxnState> terminal = EnumSet.noneOf(TxnState.class);
        for (TxnState s : TxnState.values()) {
            if (TxnStateMachine.isTerminal(s)) {
                terminal.add(s);
            }
        }
        assertEquals(EnumSet.of(SUCCESS, FAILED, REVERSED), terminal);
    }

    @ParameterizedTest
    @EnumSource(TxnState.class)
    void noStateCanMoveToItself(TxnState state) {
        assertFalse(TxnStateMachine.canTransition(state, state));
    }

    @Test
    void everyStateCanBeReachedFromInitiated() {
        assertEquals(EnumSet.allOf(TxnState.class), reachableFrom(INITIATED));
    }

    /** No dead ends: a payment can never get stuck somewhere with no way to finish. */
    @ParameterizedTest
    @EnumSource(TxnState.class)
    void everyStateCanStillReachATerminalState(TxnState state) {
        assertTrue(reachableFrom(state).stream().anyMatch(TxnStateMachine::isTerminal),
                state + " cannot reach any terminal state");
    }

    private static Set<TxnState> reachableFrom(TxnState start) {
        Set<TxnState> seen = EnumSet.of(start);
        Deque<TxnState> queue = new ArrayDeque<>(List.of(start));
        while (!queue.isEmpty()) {
            for (TxnState next : TxnStateMachine.allowedFrom(queue.poll())) {
                if (seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        return seen;
    }
}

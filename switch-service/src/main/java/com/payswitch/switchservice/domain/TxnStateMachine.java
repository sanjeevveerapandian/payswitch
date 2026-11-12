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

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The ONLY allowed moves between payment states. Everything not listed here is illegal.
 * Pure Java: no Spring, no database. The whole rulebook is the table below.
 */
public final class TxnStateMachine {

    private static final Map<TxnState, Set<TxnState>> ALLOWED = new EnumMap<>(TxnState.class);

    static {
        // Nothing sent yet: send the debit, or give up (e.g. unknown bank handle).
        allow(INITIATED, DEBIT_SENT, FAILED);
        // Bank A answered yes / answered no / never answered (so we reverse to be safe).
        allow(DEBIT_SENT, DEBITED, FAILED, REVERSAL_SENT);
        // Money is in settlement: pass it on to Anu, or send it back.
        allow(DEBITED, CREDIT_SENT, REVERSAL_SENT);
        // Bank B answered yes / answered no (send the money back) / never answered.
        allow(CREDIT_SENT, SUCCESS, REVERSAL_SENT, DEEMED_APPROVED);
        // Later check with Bank B: it did credit Anu / it did not (send the money back).
        allow(DEEMED_APPROVED, SUCCESS, REVERSAL_SENT);
        // Bank A returned the money / said "there was no debit to undo" (so Ravi never paid).
        allow(REVERSAL_SENT, REVERSED, FAILED);
        // Terminal: the story is over. Nothing may leave.
        allow(SUCCESS);
        allow(FAILED);
        allow(REVERSED);
    }

    private TxnStateMachine() {
    }

    private static void allow(TxnState from, TxnState... targets) {
        Set<TxnState> set = EnumSet.noneOf(TxnState.class);
        Collections.addAll(set, targets);
        ALLOWED.put(from, Collections.unmodifiableSet(set));
    }

    /** States you may move to from {@code from}. Empty for terminal states. */
    public static Set<TxnState> allowedFrom(TxnState from) {
        return ALLOWED.get(from);
    }

    public static boolean canTransition(TxnState from, TxnState to) {
        return ALLOWED.get(from).contains(to);
    }

    public static boolean isTerminal(TxnState state) {
        return ALLOWED.get(state).isEmpty();
    }

    /** @throws IllegalStateTransitionException if the move is not in the table */
    public static void check(String txnId, TxnState from, TxnState to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateTransitionException(txnId, from, to);
        }
    }
}

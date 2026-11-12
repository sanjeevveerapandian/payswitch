package com.payswitch.switchservice;

import com.payswitch.switchservice.domain.Txn;
import com.payswitch.switchservice.domain.TxnState;
import com.payswitch.switchservice.domain.TxnStateMachine;
import com.payswitch.switchservice.service.TransactionService;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Base for switch tests that need the database. Subclasses share one Spring context, so the
 * Postgres container starts once. Each test uses fresh random txnIds, so tests cannot collide.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
public abstract class SwitchIntegrationTest {

    @Autowired
    protected TransactionService service;

    protected static String newTxnId() {
        return "TXN-" + UUID.randomUUID();
    }

    /** Ravi (Bank A) pays Rs 200 to Anu's shop (Bank B). Starts in INITIATED. */
    protected Txn raviPaysAnu() {
        return service.create(newTxnId(), "ravi@banka", "anu@bankb", 20_000);
    }

    /** Creates a payment and walks it, by legal moves only, until it is in {@code target}. */
    protected Txn paymentIn(TxnState target) {
        Txn txn = raviPaysAnu();
        for (TxnState step : pathTo(target)) {
            txn = service.transition(txn.txnId(), step, "test setup");
        }
        return txn;
    }

    /** Shortest list of legal moves from INITIATED to {@code target} (breadth-first search). */
    protected static List<TxnState> pathTo(TxnState target) {
        Map<TxnState, TxnState> cameFrom = new EnumMap<>(TxnState.class);
        Deque<TxnState> queue = new ArrayDeque<>(List.of(TxnState.INITIATED));
        while (!queue.isEmpty()) {
            TxnState current = queue.poll();
            for (TxnState next : TxnStateMachine.allowedFrom(current)) {
                if (next != TxnState.INITIATED && !cameFrom.containsKey(next)) {
                    cameFrom.put(next, current);
                    queue.add(next);
                }
            }
        }
        List<TxnState> path = new ArrayList<>();
        for (TxnState s = target; s != TxnState.INITIATED; s = cameFrom.get(s)) {
            path.add(0, s);
        }
        return path;
    }
}

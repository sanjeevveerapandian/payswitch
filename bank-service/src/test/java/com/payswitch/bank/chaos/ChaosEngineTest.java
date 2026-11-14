package com.payswitch.bank.chaos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.payswitch.common.bank.BankResponse;
import com.payswitch.common.bank.BankResult;
import com.payswitch.common.bank.DeclineReason;
import com.payswitch.common.bank.OpType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The chaos rules on their own: no Spring, no database, no HTTP. */
class ChaosEngineTest {

    private static final ChaosSettings MIXED = new ChaosSettings(0.2, 0.1, 0, 0.3, null);

    /**
     * Points well inside each band. Exactly ON an edge is not testable: in double arithmetic
     * 0.2 + 0.1 is 0.30000000000000004, so "0.30" lands in the ERROR band. That is fine for
     * probabilities (a roll hits an exact edge with chance ~0). It is never fine for money,
     * which is why money is always long paise.
     */
    @ParameterizedTest(name = "roll {0} -> {1}")
    @CsvSource({
            "0.00,  DECLINE",          // [0.0, 0.2)
            "0.19,  DECLINE",
            "0.21,  ERROR",            // [0.2, 0.3)
            "0.29,  ERROR",
            "0.31,  DROP_RESPONSE",    // [0.3, 0.6)
            "0.59,  DROP_RESPONSE",
            "0.61,  PROCEED",          // [0.6, 1.0)
            "0.99,  PROCEED",
    })
    void eachRollFallsIntoExactlyOneBand(double roll, ChaosDecision expected) {
        assertEquals(expected, ChaosEngine.decide(MIXED, roll));
    }

    @Test
    void allZeroMeansAlwaysProceed() {
        for (double roll = 0; roll < 1; roll += 0.01) {
            assertEquals(ChaosDecision.PROCEED, ChaosEngine.decide(ChaosSettings.NONE, roll));
        }
    }

    @Test
    void sameSeedGivesTheSameDecisions() {
        ChaosSettings seeded = new ChaosSettings(0.3, 0.3, 0, 0.2, 42L);
        ChaosEngine engine = new ChaosEngine(Duration.ZERO);

        engine.configure(seeded);
        List<String> first = runCalls(engine, 50);
        engine.configure(seeded);                    // same seed again: the dice restart
        List<String> second = runCalls(engine, 50);
        engine.configure(new ChaosSettings(0.3, 0.3, 0, 0.2, 43L));
        List<String> otherSeed = runCalls(engine, 50);

        assertEquals(first, second);
        assertNotEquals(first, otherSeed);
    }

    @Test
    void declineAndErrorNeverRunTheOperation() {
        ChaosEngine engine = new ChaosEngine(Duration.ZERO);
        AtomicInteger ran = new AtomicInteger();

        engine.configure(new ChaosSettings(1, 0, 0, 0, null));
        BankResponse declined = engine.run(OpType.DEBIT, "T1", () -> {
            ran.incrementAndGet();
            return BankResponse.success("T1", OpType.DEBIT);
        });
        engine.configure(new ChaosSettings(0, 1, 0, 0, null));
        assertThrows(ChaosFailureException.class, () -> engine.run(OpType.DEBIT, "T2", () -> {
            ran.incrementAndGet();
            return BankResponse.success("T2", OpType.DEBIT);
        }));

        assertEquals(0, ran.get());
        assertEquals(BankResult.DECLINED, declined.result());
        assertEquals(DeclineReason.SIMULATED_DECLINE, declined.reason());
    }

    @Test
    void dropResponseRunsTheOperationThenWaitsBeforeAnswering() {
        ChaosEngine engine = new ChaosEngine(Duration.ofMillis(300));
        engine.configure(new ChaosSettings(0, 0, 0, 1, null));
        BankResponse real = BankResponse.success("T1", OpType.DEBIT);
        AtomicInteger ran = new AtomicInteger();

        long start = System.nanoTime();
        BankResponse answer = engine.run(OpType.DEBIT, "T1", () -> {
            ran.incrementAndGet();
            return real;
        });
        long tookMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(1, ran.get());
        assertSame(real, answer);
        assertEquals(true, tookMs >= 300, "took " + tookMs + " ms");
    }

    @Test
    void invalidSettingsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ChaosSettings(-0.1, 0, 0, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new ChaosSettings(1.5, 0, 0, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new ChaosSettings(Double.NaN, 0, 0, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new ChaosSettings(0.5, 0.4, 0, 0.2, null));
        assertThrows(IllegalArgumentException.class, () -> new ChaosSettings(0, 0, -1, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new ChaosSettings(0, 0, 60_001, 0, null));
    }

    /** What chaos did to each of n calls, as text: PROCEED / DECLINE / ERROR / DROP_RESPONSE. */
    private static List<String> runCalls(ChaosEngine engine, int n) {
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            AtomicInteger ran = new AtomicInteger();
            try {
                BankResponse r = engine.run(OpType.DEBIT, "T" + i, () -> {
                    ran.incrementAndGet();
                    return BankResponse.success("T", OpType.DEBIT);
                });
                if (r.result() == BankResult.DECLINED) {
                    seen.add("DECLINE");
                } else {
                    seen.add(ran.get() == 1 ? "PROCEED_OR_DROP" : "?");
                }
            } catch (ChaosFailureException e) {
                seen.add("ERROR");
            }
        }
        return seen;
    }
}

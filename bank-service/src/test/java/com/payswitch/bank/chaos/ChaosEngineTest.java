package com.payswitch.bank.chaos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.payswitch.bank.metrics.BankMetrics;
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
        ChaosEngine engine = new ChaosEngine(Duration.ZERO, BankMetrics.inMemory());

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
        ChaosEngine engine = new ChaosEngine(Duration.ZERO, BankMetrics.inMemory());
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
        ChaosEngine engine = new ChaosEngine(Duration.ofMillis(300), BankMetrics.inMemory());
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
        assertThrows(IllegalArgumentException.class, () -> new ChaosSettings(0, 0, 0, 0, null, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new ChaosSettings(0, 0, 0, 0, null, 0, 60_001));
    }

    /** Phase 10: "delay random 0-300 ms" = delayMs 0 + delayJitterMs 300. */
    @Test
    void randomDelayStaysInRangeAndActuallyVaries() {
        ChaosSettings jittery = new ChaosSettings(0, 0, 100, 0, null, 0, 300);
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            long d = ChaosEngine.delayFor(jittery);
            assertEquals(true, d >= 100 && d <= 400, "delay " + d);
            seen.add(d);
        }
        assertEquals(true, seen.size() > 100, "only " + seen.size() + " different delays");
        assertEquals(100, ChaosEngine.delayFor(new ChaosSettings(0, 0, 100, 0, null)));
    }

    /** The random delay must not use the seeded dice: the same seed still gives the same decisions. */
    @Test
    void randomDelayDoesNotChangeSeededDecisions() {
        ChaosEngine plain = new ChaosEngine(Duration.ZERO, BankMetrics.inMemory());
        ChaosEngine jittery = new ChaosEngine(Duration.ZERO, BankMetrics.inMemory());
        plain.configure(new ChaosSettings(0.3, 0.3, 0, 0.2, 42L, 0, 0));
        jittery.configure(new ChaosSettings(0.3, 0.3, 0, 0.2, 42L, 0, 2));
        assertEquals(runCalls(plain, 50), runCalls(jittery, 50));
    }

    /** Phase 12: a one-shot failure overrides one call, and the seeded sequence after it is unchanged. */
    @Test
    void aOneShotFailureHitsOneCallAndDoesNotShiftTheSeededSequence() {
        ChaosEngine plain = new ChaosEngine(Duration.ZERO, BankMetrics.inMemory());
        ChaosEngine armed = new ChaosEngine(Duration.ZERO, BankMetrics.inMemory());
        plain.configure(new ChaosSettings(0.3, 0.3, 0, 0, 42L, 0, 0));
        armed.configure(new ChaosSettings(0.3, 0.3, 0, 0, 42L, 0, 0));
        armed.armOnce("DEBIT", "error");

        List<String> expected = runCalls(plain, 30);
        List<String> actual = runCalls(armed, 30);

        assertEquals("ERROR", actual.get(0), "the armed call");
        assertEquals(expected.subList(1, 30), actual.subList(1, 30), "every later call as if nothing was armed");
        assertEquals(java.util.Map.of(), armed.pendingOnce());
    }

    @Test
    void aOneShotStatusCheckErrorFailsOneStatusCheck() {
        ChaosEngine engine = new ChaosEngine(Duration.ZERO, BankMetrics.inMemory());
        engine.armOnce("status_check", "error");
        assertThrows(ChaosFailureException.class, () -> engine.runStatusCheck("T1", () -> "checked"));
        assertEquals("checked", engine.runStatusCheck("T2", () -> "checked"));
    }

    // ---- Phase 13: chaos switches itself off -------------------------------------------------------

    /** A clock the test moves by hand. */
    static final class HandClock extends java.time.Clock {
        private java.time.Instant now = java.time.Instant.parse("2026-01-01T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public java.time.Instant instant() {
            return now;
        }
    }

    private static final ChaosSettings ALWAYS_ERROR = new ChaosSettings(0, 1.0, 0, 0, null, 1.0);

    @Test
    void chaosResetsItselfFiveMinutesAfterTheLastChange() {
        HandClock clock = new HandClock();
        ChaosEngine engine = new ChaosEngine(Duration.ZERO, Duration.ofMinutes(5), BankMetrics.inMemory(), clock);
        engine.configure(ALWAYS_ERROR);
        engine.armOnce("CREDIT", "decline");

        clock.advance(Duration.ofMinutes(4).plusSeconds(59));
        assertEquals(ALWAYS_ERROR, engine.settings(), "still on just before the limit");
        assertEquals(java.util.Map.of("CREDIT", ChaosDecision.DECLINE), engine.pendingOnce());
        assertThrows(ChaosFailureException.class, () -> engine.run(OpType.DEBIT, "T1",
                () -> BankResponse.success("T1", OpType.DEBIT)));

        clock.advance(Duration.ofSeconds(1));
        // A bank call is the first thing to notice: it already runs without chaos.
        assertEquals(BankResult.SUCCESS, engine.run(OpType.DEBIT, "T2",
                () -> BankResponse.success("T2", OpType.DEBIT)).result());
        assertEquals("checked", engine.runStatusCheck("T3", () -> "checked"));
        assertEquals(ChaosSettings.NONE, engine.settings());
        assertEquals(java.util.Map.of(), engine.pendingOnce(), "one-shot rules go too");
    }

    @Test
    void everyChangeStartsTheFiveMinutesAgain() {
        HandClock clock = new HandClock();
        ChaosEngine engine = new ChaosEngine(Duration.ZERO, Duration.ofMinutes(5), BankMetrics.inMemory(), clock);
        engine.configure(ALWAYS_ERROR);
        clock.advance(Duration.ofMinutes(4));
        engine.armOnce("DEBIT", "error");                 // a change: the clock starts again
        clock.advance(Duration.ofMinutes(4));
        assertEquals(ALWAYS_ERROR, engine.settings());
        clock.advance(Duration.ofMinutes(1));
        assertEquals(ChaosSettings.NONE, engine.settings());
    }

    @Test
    void withAutoResetZeroChaosStaysOnForever() {
        HandClock clock = new HandClock();
        ChaosEngine engine = new ChaosEngine(Duration.ZERO, Duration.ZERO, BankMetrics.inMemory(), clock);
        engine.configure(ALWAYS_ERROR);
        clock.advance(Duration.ofDays(1));
        assertEquals(ALWAYS_ERROR, engine.settings());
    }

    @Test
    void anUnusedOneShotRuleCanBeTakenBack() {
        ChaosEngine engine = new ChaosEngine(Duration.ZERO, BankMetrics.inMemory());
        engine.armOnce("DEBIT", "drop_response");
        engine.armOnce("CREDIT", "error");
        assertEquals(true, engine.disarmOnce("debit"));
        assertEquals(false, engine.disarmOnce("DEBIT"), "nothing left to take back");
        assertEquals(java.util.Map.of("CREDIT", ChaosDecision.ERROR), engine.pendingOnce());
        assertEquals(BankResult.SUCCESS, engine.run(OpType.DEBIT, "T1",
                () -> BankResponse.success("T1", OpType.DEBIT)).result());
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

package com.payswitch.switchservice.retry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Backoff and jitter, checked without ever really sleeping: the sleeper just records the waits. */
class RetryPolicyTest {

    private static final RetryPolicy POLICY = new RetryPolicy(Duration.ofSeconds(1), 2.0, 0.2, 5);

    @Test
    void theFirstAttemptIsImmediateAndEachWaitDoubles() {
        // random 0.5 = exactly in the middle of the jitter range = no jitter at all
        assertEquals(Duration.ZERO, POLICY.delayBefore(1, 0.5));
        assertEquals(Duration.ofSeconds(1), POLICY.delayBefore(2, 0.5));
        assertEquals(Duration.ofSeconds(2), POLICY.delayBefore(3, 0.5));
        assertEquals(Duration.ofSeconds(4), POLICY.delayBefore(4, 0.5));
        assertEquals(Duration.ofSeconds(8), POLICY.delayBefore(5, 0.5));
    }

    @Test
    void jitterStaysWithinPlusMinus20Percent() {
        assertEquals(Duration.ofMillis(800), POLICY.delayBefore(2, 0.0));      // lowest possible
        assertEquals(Duration.ofMillis(1600), POLICY.delayBefore(3, 0.0));
        assertTrue(POLICY.delayBefore(2, 0.999_999).toMillis() < 1200);        // highest, just under
        assertTrue(POLICY.delayBefore(4, 0.999_999).toMillis() < 4800);
    }

    @Test
    void realRandomDelaysGrowExponentiallyAndStayInBounds() {
        Random random = new Random(42);
        for (int run = 0; run < 200; run++) {
            List<Duration> waits = new ArrayList<>();
            AtomicInteger calls = new AtomicInteger();

            String result = POLICY.run(() -> {
                calls.incrementAndGet();
                return "UNKNOWN";
            }, r -> false, waits::add, random::nextDouble);

            assertEquals("UNKNOWN", result);
            assertEquals(5, calls.get());                     // maxAttempts
            assertEquals(4, waits.size());                    // one wait between each pair
            for (int i = 0; i < waits.size(); i++) {
                long planned = 1000L << i;                    // 1s, 2s, 4s, 8s
                long ms = waits.get(i).toMillis();
                assertTrue(ms >= planned * 0.8 && ms <= planned * 1.2,
                        "wait " + (i + 1) + " was " + ms + " ms, planned " + planned);
            }
        }
    }

    @Test
    void stopsAsSoonAsTheAnswerIsFinal() {
        List<Duration> waits = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();

        String result = POLICY.run(() -> calls.incrementAndGet() < 3 ? "UNKNOWN" : "SUCCESS",
                "SUCCESS"::equals, waits::add, () -> 0.5);

        assertEquals("SUCCESS", result);
        assertEquals(3, calls.get());
        assertEquals(List.of(Duration.ofSeconds(1), Duration.ofSeconds(2)), waits);
    }

    @Test
    void aFinalAnswerFirstTimeMeansNoWaitAtAll() {
        List<Duration> waits = new ArrayList<>();

        POLICY.run(() -> "SUCCESS", "SUCCESS"::equals, waits::add, () -> 0.5);

        assertTrue(waits.isEmpty());
    }

    @Test
    void anInterruptStopsRetrying() {
        AtomicInteger calls = new AtomicInteger();

        POLICY.run(() -> {
            calls.incrementAndGet();
            return "UNKNOWN";
        }, r -> false, d -> {
            throw new InterruptedException();
        }, () -> 0.5);

        assertEquals(1, calls.get());
        assertTrue(Thread.interrupted());                     // the flag is kept (and cleared here)
    }

    /**
     * Why jitter exists. 1,000 payments all failed at the same second (a bank blipped). Without
     * jitter they would all retry at exactly the same instant and hit the recovering bank as one
     * spike. With +-20% jitter the retries spread over a 0.4 s window instead.
     */
    @Test
    void jitterSpreadsOutAThunderingHerd() {
        RetryPolicy noJitter = new RetryPolicy(Duration.ofSeconds(1), 2.0, 0.0, 5);
        Random random = new Random(7);
        long minWithJitter = Long.MAX_VALUE;
        long maxWithJitter = 0;
        for (int client = 0; client < 1000; client++) {
            assertEquals(Duration.ofSeconds(1), noJitter.delayBefore(2, random.nextDouble()));
            long ms = POLICY.delayBefore(2, random.nextDouble()).toMillis();
            minWithJitter = Math.min(minWithJitter, ms);
            maxWithJitter = Math.max(maxWithJitter, ms);
        }
        assertTrue(maxWithJitter - minWithJitter > 350, minWithJitter + ".." + maxWithJitter);
    }

    @Test
    void badConfigIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(null, 2, 0.2, 3));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(Duration.ofSeconds(1), 0.5, 0.2, 3));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(Duration.ofSeconds(1), 2, 1.0, 3));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(Duration.ofSeconds(1), 2, 0.2, 0));
    }
}

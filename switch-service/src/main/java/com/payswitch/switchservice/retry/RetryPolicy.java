package com.payswitch.switchservice.retry;

import java.time.Duration;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Exponential backoff with jitter. Bound from switch.retry.* in application.yml.
 *
 * <p>Attempt 1 runs at once. Before attempt n (n >= 2) we wait
 * {@code baseDelay * multiplier^(n-2)}, then nudge it randomly by up to +-jitter:
 *
 * <pre>
 *   base 1s, x2, jitter 0.2, 4 attempts:
 *   attempt 1: now
 *   attempt 2: after 1s +-20%  (0.8s .. 1.2s)
 *   attempt 3: after 2s +-20%  (1.6s .. 2.4s)
 *   attempt 4: after 4s +-20%  (3.2s .. 4.8s)
 * </pre>
 *
 * @param baseDelay   wait before the 2nd attempt (before jitter)
 * @param multiplier  each wait is this many times the previous one
 * @param jitter      0.2 = each wait is randomly between 80% and 120% of its planned value
 * @param maxAttempts total tries, including the first
 */
@ConfigurationProperties("switch.retry")
public record RetryPolicy(Duration baseDelay, double multiplier, double jitter, int maxAttempts) {

    /** How the policy waits. Real code sleeps; tests record the waits instead. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    public RetryPolicy {
        if (baseDelay == null || baseDelay.isNegative()) {
            throw new IllegalArgumentException("switch.retry.base-delay must be set and not negative");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("switch.retry.multiplier must be at least 1");
        }
        if (jitter < 0.0 || jitter >= 1.0) {
            throw new IllegalArgumentException("switch.retry.jitter must be in [0, 1)");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("switch.retry.max-attempts must be at least 1");
        }
    }

    /**
     * How long to wait before attempt number {@code attempt} (1-based).
     *
     * @param random a number in [0, 1); 0.5 means "no jitter", 0 the shortest, ~1 the longest
     */
    public Duration delayBefore(int attempt, double random) {
        if (attempt <= 1) {
            return Duration.ZERO;
        }
        double planned = baseDelay.toNanos() * Math.pow(multiplier, attempt - 2);
        double nudge = 1.0 + jitter * (2.0 * random - 1.0);   // in [1 - jitter, 1 + jitter)
        return Duration.ofNanos(Math.round(planned * nudge));
    }

    /**
     * Calls {@code call} until {@code done} accepts the result or the attempts run out, waiting
     * between attempts. Returns the last result either way. Stops early if interrupted.
     */
    public <T> T run(Supplier<T> call, Predicate<T> done, Sleeper sleeper, DoubleSupplier random) {
        T result = call.get();
        for (int attempt = 2; attempt <= maxAttempts && !done.test(result); attempt++) {
            try {
                sleeper.sleep(delayBefore(attempt, random.getAsDouble()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return result;
            }
            result = call.get();
        }
        return result;
    }
}

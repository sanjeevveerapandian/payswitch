package com.payswitch.switchservice.retry;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** The retry policy from config, with a real clock and real randomness. */
@Component
public class Retrier {

    private final RetryPolicy policy;

    public Retrier(RetryPolicy policy) {
        this.policy = policy;
    }

    public <T> T run(Supplier<T> call, Predicate<T> done) {
        return policy.run(call, done, Thread::sleep, () -> ThreadLocalRandom.current().nextDouble());
    }

    public int maxAttempts() {
        return policy.maxAttempts();
    }
}

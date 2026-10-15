package com.flowledger.payment.outbox;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/**
 * Capped exponential backoff with "equal jitter": the delay for attempt n is between half and all
 * of min(max, initial * 2^(n-1)), so retries spread out but never exceed the cap.
 */
public final class RetryBackoff {

    private final Duration initial;
    private final Duration max;
    private final DoubleSupplier random;

    public RetryBackoff(Duration initial, Duration max, DoubleSupplier random) {
        this.initial = initial;
        this.max = max;
        this.random = random;
    }

    public Duration delayFor(int attempt) {
        long cappedMillis = max.toMillis();
        long base = initial.toMillis();
        // stop doubling once the cap is reached, so large attempt counts cannot overflow
        for (int i = 1; i < attempt && base < cappedMillis; i++) {
            base *= 2;
        }
        long ceiling = Math.min(base, cappedMillis);
        long half = ceiling / 2;
        return Duration.ofMillis(half + (long) (random.getAsDouble() * (ceiling - half)));
    }
}

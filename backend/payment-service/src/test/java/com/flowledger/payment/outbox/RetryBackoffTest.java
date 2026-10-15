package com.flowledger.payment.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RetryBackoffTest {

    private static final Duration INITIAL = Duration.ofSeconds(5);
    private static final Duration MAX = Duration.ofMinutes(15);

    @Test
    void shouldDoubleEachAttemptWithinHalfToFullOfTheCeiling() {
        RetryBackoff lowest = new RetryBackoff(INITIAL, MAX, () -> 0.0);
        RetryBackoff highest = new RetryBackoff(INITIAL, MAX, () -> 1.0);

        assertThat(lowest.delayFor(1)).isEqualTo(Duration.ofMillis(2500));
        assertThat(highest.delayFor(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(lowest.delayFor(2)).isEqualTo(Duration.ofSeconds(5));
        assertThat(highest.delayFor(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(highest.delayFor(4)).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    void shouldNeverExceedTheCapEvenForManyAttempts() {
        RetryBackoff highest = new RetryBackoff(INITIAL, MAX, () -> 1.0);
        RetryBackoff lowest = new RetryBackoff(INITIAL, MAX, () -> 0.0);

        assertThat(highest.delayFor(9)).isEqualTo(MAX);
        assertThat(highest.delayFor(1_000_000)).isEqualTo(MAX);
        assertThat(lowest.delayFor(1_000_000)).isEqualTo(MAX.dividedBy(2));
    }

    @Test
    void shouldSpreadRetriesWithJitter() {
        RetryBackoff middle = new RetryBackoff(INITIAL, MAX, () -> 0.5);

        assertThat(middle.delayFor(1)).isEqualTo(Duration.ofMillis(3750));
    }
}

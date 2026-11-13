// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.retry;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Retries of {@code RETRYABLE} failures on the <b>same</b> stage, before the chain moves on.
 *
 * <ul>
 *   <li>{@code maxAttempts}: total calls per stage, first one included (default 3; 1 = no retry).</li>
 *   <li>{@code baseDelay}: first backoff, doubled at each retry (default 500 ms), with jitter.</li>
 *   <li>{@code maxDelay}: cap on the computed backoff (default 5 s), applied after jitter.</li>
 *   <li>{@code maxRetryAfter}: cap on a {@code Retry-After} the provider asks for (default 5 s). It is
 *       honoured exactly, without jitter, up to this cap.</li>
 *   <li>{@code jitterSpread}: half-width of the jitter factor (default 0.5).</li>
 * </ul>
 */
public final class RetryPolicy {

    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final Duration maxRetryAfter;
    private final double jitterSpread;
    private final DoubleSupplier random;

    private RetryPolicy(Builder b) {
        this.maxAttempts = b.maxAttempts;
        this.baseDelay = b.baseDelay;
        this.maxDelay = b.maxDelay;
        this.maxRetryAfter = b.maxRetryAfter;
        this.jitterSpread = b.jitterSpread;
        this.random = b.random;
    }

    public static RetryPolicy defaults() {
        return builder().build();
    }

    /** One call per stage: transient errors move straight to the next stage. */
    public static RetryPolicy none() {
        return builder().maxAttempts(1).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /**
     * The wait before retry number {@code attempt + 1}, after call {@code attempt} failed.
     *
     * @param attempt    the 1-based attempt that just failed
     * @param retryAfter the provider's requested wait, or {@code null}
     */
    public Duration delayBeforeRetry(int attempt, Duration retryAfter) {
        if (retryAfter != null) {
            return retryAfter.compareTo(maxRetryAfter) > 0 ? maxRetryAfter : retryAfter;
        }
        long deterministic = Backoff.exponentialMillis(baseDelay.toMillis(), attempt);
        long jittered = Backoff.jitterMillis(deterministic, jitterSpread, random.getAsDouble());
        return Duration.ofMillis(Math.min(jittered, maxDelay.toMillis()));
    }

    public static final class Builder {
        private int maxAttempts = 3;
        private Duration baseDelay = Duration.ofMillis(500);
        private Duration maxDelay = Duration.ofSeconds(5);
        private Duration maxRetryAfter = Duration.ofSeconds(5);
        private double jitterSpread = 0.5;
        private DoubleSupplier random = () -> ThreadLocalRandom.current().nextDouble();

        private Builder() {
        }

        public Builder maxAttempts(int value) {
            if (value < 1) {
                throw new IllegalArgumentException("maxAttempts must be at least 1");
            }
            this.maxAttempts = value;
            return this;
        }

        public Builder baseDelay(Duration value) {
            this.baseDelay = nonNegative(value, "baseDelay");
            return this;
        }

        public Builder maxDelay(Duration value) {
            this.maxDelay = nonNegative(value, "maxDelay");
            return this;
        }

        public Builder maxRetryAfter(Duration value) {
            this.maxRetryAfter = nonNegative(value, "maxRetryAfter");
            return this;
        }

        public Builder jitterSpread(double value) {
            if (value < 0 || value >= 1) {
                throw new IllegalArgumentException("jitterSpread must be in [0, 1)");
            }
            this.jitterSpread = value;
            return this;
        }

        /** Source of uniform numbers in {@code [0, 1)}; inject a constant for deterministic tests. */
        public Builder random(DoubleSupplier value) {
            this.random = Objects.requireNonNull(value, "random");
            return this;
        }

        public RetryPolicy build() {
            return new RetryPolicy(this);
        }

        private static Duration nonNegative(Duration value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isNegative()) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
            return value;
        }
    }
}

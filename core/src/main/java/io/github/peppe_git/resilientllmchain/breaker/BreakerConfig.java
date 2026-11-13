// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.breaker;

import java.time.Duration;
import java.util.Objects;

/**
 * Settings of the per-stage breaker. The defaults are generic starting points, not measurements: tune
 * them on your own traffic (see {@code docs/configuration.md}).
 *
 * <ul>
 *   <li>{@code failureThreshold} (3): consecutive failures, after retries, that open the cooldown.</li>
 *   <li>{@code cooldown} (60 s): how long a stage is skipped after transient failures. When it expires
 *       the stage is half-open: the next call tries it; a failure reopens the cooldown at once.</li>
 *   <li>{@code permanentCooldown} (1 h): cooldown after a permanent error (model not found or retired),
 *       opened at the first occurrence.</li>
 *   <li>{@code stallAfter} (60 s) and {@code stallThreshold} (2): the stage counts as open while at
 *       least {@code stallThreshold} of its calls have been in flight for {@code stallAfter} or more.
 *       {@code Duration.ZERO} disables stall detection.</li>
 *   <li>{@code sharedRefresh} (5 s): how often a stage's shared verdict is read from the
 *       {@code SharedVerdictStore}.</li>
 * </ul>
 */
public final class BreakerConfig {

    private final boolean enabled;
    private final int failureThreshold;
    private final Duration cooldown;
    private final Duration permanentCooldown;
    private final Duration stallAfter;
    private final int stallThreshold;
    private final Duration sharedRefresh;

    private BreakerConfig(Builder b) {
        this.enabled = b.enabled;
        this.failureThreshold = b.failureThreshold;
        this.cooldown = b.cooldown;
        this.permanentCooldown = b.permanentCooldown;
        this.stallAfter = b.stallAfter;
        this.stallThreshold = b.stallThreshold;
        this.sharedRefresh = b.sharedRefresh;
    }

    public static BreakerConfig defaults() {
        return builder().build();
    }

    /** A breaker that never opens. */
    public static BreakerConfig disabled() {
        return builder().enabled(false).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean enabled() {
        return enabled;
    }

    public int failureThreshold() {
        return failureThreshold;
    }

    public Duration cooldown() {
        return cooldown;
    }

    public Duration permanentCooldown() {
        return permanentCooldown;
    }

    public Duration stallAfter() {
        return stallAfter;
    }

    public int stallThreshold() {
        return stallThreshold;
    }

    public Duration sharedRefresh() {
        return sharedRefresh;
    }

    public static final class Builder {
        private boolean enabled = true;
        private int failureThreshold = 3;
        private Duration cooldown = Duration.ofSeconds(60);
        private Duration permanentCooldown = Duration.ofHours(1);
        private Duration stallAfter = Duration.ofSeconds(60);
        private int stallThreshold = 2;
        private Duration sharedRefresh = Duration.ofSeconds(5);

        private Builder() {
        }

        public Builder enabled(boolean value) {
            this.enabled = value;
            return this;
        }

        public Builder failureThreshold(int value) {
            this.failureThreshold = atLeastOne(value, "failureThreshold");
            return this;
        }

        public Builder cooldown(Duration value) {
            this.cooldown = nonNegative(value, "cooldown");
            return this;
        }

        public Builder permanentCooldown(Duration value) {
            this.permanentCooldown = nonNegative(value, "permanentCooldown");
            return this;
        }

        public Builder stallAfter(Duration value) {
            this.stallAfter = nonNegative(value, "stallAfter");
            return this;
        }

        public Builder stallThreshold(int value) {
            this.stallThreshold = atLeastOne(value, "stallThreshold");
            return this;
        }

        public Builder sharedRefresh(Duration value) {
            this.sharedRefresh = nonNegative(value, "sharedRefresh");
            return this;
        }

        public BreakerConfig build() {
            return new BreakerConfig(this);
        }

        private static int atLeastOne(int value, String name) {
            if (value < 1) {
                throw new IllegalArgumentException(name + " must be at least 1");
            }
            return value;
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

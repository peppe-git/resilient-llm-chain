// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Every notion of time the chain uses: reading the time, one-shot timers and sleeping. Hedge delays,
 * cooldowns, stall detection, call timeouts and retry backoff all go through it, which is what makes
 * them testable with {@link ManualChainClock}.
 */
public interface ChainClock {

    /** The current instant. */
    Instant instant();

    /** The current time in epoch milliseconds. */
    default long millis() {
        return instant().toEpochMilli();
    }

    /**
     * Runs {@code action} once after {@code delay}. Actions must be short and must not block: the chain
     * only uses them to post events and complete futures.
     */
    Timer schedule(Duration delay, Runnable action);

    /** Blocks the calling thread for {@code duration}. */
    void sleep(Duration duration) throws InterruptedException;

    /** This clock as a {@link Clock} in UTC, for APIs that take one. */
    default Clock asClock() {
        ChainClock self = this;
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                throw new UnsupportedOperationException("a chain clock is always UTC");
            }

            @Override
            public Instant instant() {
                return self.instant();
            }
        };
    }

    /** A scheduled action that can still be cancelled. */
    @FunctionalInterface
    interface Timer {
        /** Prevents the action from running, if it has not run yet. Idempotent. */
        void cancel();
    }

    /** Wall-clock time, with timers on a shared daemon thread. */
    static ChainClock system() {
        return SystemChainClock.DEFAULT;
    }

    /** Time read from {@code clock}; timers and sleeps still use real elapsed time. */
    static ChainClock of(Clock clock) {
        return new SystemChainClock(clock);
    }
}

// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** {@link ChainClock} on real time. Timer actions run on one shared daemon thread. */
final class SystemChainClock implements ChainClock {

    static final SystemChainClock DEFAULT = new SystemChainClock(Clock.systemUTC());

    private static final ScheduledExecutorService TIMERS = timers();

    private final Clock clock;

    SystemChainClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    private static ScheduledExecutorService timers() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "resilient-llm-chain-timer");
            thread.setDaemon(true);
            return thread;
        });
        // Cancelled hedge timers are the common case: drop them instead of keeping them until due.
        executor.setRemoveOnCancelPolicy(true);
        return Executors.unconfigurableScheduledExecutorService(executor);
    }

    @Override
    public Instant instant() {
        return clock.instant();
    }

    @Override
    public long millis() {
        return clock.millis();
    }

    @Override
    public Timer schedule(Duration delay, Runnable action) {
        Objects.requireNonNull(action, "action");
        long nanos = Math.max(0L, delay.toNanos());
        ScheduledFuture<?> future = TIMERS.schedule(action, nanos, TimeUnit.NANOSECONDS);
        return () -> future.cancel(false);
    }

    @Override
    public void sleep(Duration duration) throws InterruptedException {
        if (!duration.isNegative() && !duration.isZero()) {
            Thread.sleep(duration);
        }
    }
}

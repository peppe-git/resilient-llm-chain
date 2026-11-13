// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.limit;

import io.github.peppe_git.resilientllmchain.ChainSaturatedException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * An explicit cap on concurrent chain calls, independent of thread pools. With virtual threads, fan-out
 * is cheap and thread pools no longer throttle anything; providers' rate limits and costs still need a
 * cap.
 *
 * <ul>
 *   <li>One permit per chain call, not per stage: a hedge does not take a second permit, but it only
 *       starts when at least one permit is free ({@link #availablePermits()}).</li>
 *   <li>Fair semaphore, so an interactive call is not starved behind a burst of background work.</li>
 *   <li>Background calls wait for a permit without limit. Interactive calls wait at most
 *       {@code interactiveWait}, then fail with {@link ChainSaturatedException}: a quick "busy" beats an
 *       indefinite wait for someone watching a spinner.</li>
 * </ul>
 */
public final class ConcurrencyLimiter {

    private static final ConcurrencyLimiter UNBOUNDED = new ConcurrencyLimiter(null, Duration.ZERO);

    private final Semaphore semaphore;
    private final Duration interactiveWait;
    private final AtomicLong waitNanos = new AtomicLong();
    private final AtomicLong waits = new AtomicLong();

    private ConcurrencyLimiter(Semaphore semaphore, Duration interactiveWait) {
        this.semaphore = semaphore;
        this.interactiveWait = interactiveWait;
    }

    /**
     * @param permits         concurrent chain calls allowed
     * @param interactiveWait longest wait for a permit for an interactive call
     */
    public static ConcurrencyLimiter of(int permits, Duration interactiveWait) {
        if (permits < 1) {
            throw new IllegalArgumentException("permits must be at least 1");
        }
        Objects.requireNonNull(interactiveWait, "interactiveWait");
        if (interactiveWait.isNegative()) {
            throw new IllegalArgumentException("interactiveWait must not be negative");
        }
        return new ConcurrencyLimiter(new Semaphore(permits, true), interactiveWait);
    }

    /** No cap. The default. */
    public static ConcurrencyLimiter unbounded() {
        return UNBOUNDED;
    }

    /** Runs {@code body} holding a permit, released afterwards whatever happens. */
    public <T> T call(boolean interactive, Supplier<T> body) {
        if (semaphore == null) {
            return body.get();
        }
        long start = System.nanoTime();
        boolean acquired;
        try {
            if (interactive) {
                acquired = semaphore.tryAcquire(interactiveWait.toNanos(), TimeUnit.NANOSECONDS);
            } else {
                semaphore.acquire();
                acquired = true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChainSaturatedException("interrupted while waiting for a concurrency permit");
        } finally {
            waitNanos.addAndGet(System.nanoTime() - start);
            waits.incrementAndGet();
        }
        if (!acquired) {
            throw new ChainSaturatedException("no concurrency permit within " + interactiveWait.toMillis() + " ms");
        }
        try {
            return body.get();
        } finally {
            semaphore.release();
        }
    }

    /** Free permits now; {@link Integer#MAX_VALUE} when unbounded. */
    public int availablePermits() {
        return semaphore == null ? Integer.MAX_VALUE : semaphore.availablePermits();
    }

    /** Total time spent waiting for permits. With {@link #waits()}, gives the mean wait. */
    public Duration totalWait() {
        return Duration.ofNanos(waitNanos.get());
    }

    /** Number of permit acquisitions, successful or not. */
    public long waits() {
        return waits.get();
    }
}

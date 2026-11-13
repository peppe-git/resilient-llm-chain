// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.time;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * A {@link ChainClock} that moves only when told to. Meant for tests, including tests of your own
 * hedging and breaker configuration.
 *
 * <ul>
 *   <li>{@link #advance(Duration)} moves time forward and runs every timer that falls due, in due-time
 *       order, on the calling thread, with the clock set to each timer's due time while it runs.</li>
 *   <li>{@link #sleep(Duration)} does not block: it advances the clock by the requested amount, so a
 *       retry backoff is visible in {@link #instant()} and costs no real time.</li>
 *   <li>{@link #awaitTimers(int, Duration)} lets a test wait until the code under test has scheduled
 *       its timers before advancing. The timeout is a safety net in real time, not part of the test.</li>
 * </ul>
 *
 * <p>Thread-safe.
 */
public final class ManualChainClock implements ChainClock {

    private record Task(long dueMillis, long seq, Runnable action) implements Comparable<Task> {
        @Override
        public int compareTo(Task other) {
            int byDue = Long.compare(dueMillis, other.dueMillis);
            return byDue != 0 ? byDue : Long.compare(seq, other.seq);
        }
    }

    private final Object lock = new Object();
    private final PriorityQueue<Task> tasks = new PriorityQueue<>();
    private long nowMillis;
    private long seq;

    /** Starts at an arbitrary fixed instant. */
    public ManualChainClock() {
        this(Instant.ofEpochSecond(1_000_000_000L));
    }

    public ManualChainClock(Instant start) {
        this.nowMillis = start.toEpochMilli();
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis());
    }

    @Override
    public long millis() {
        synchronized (lock) {
            return nowMillis;
        }
    }

    @Override
    public Timer schedule(Duration delay, Runnable action) {
        Objects.requireNonNull(action, "action");
        Task task;
        synchronized (lock) {
            task = new Task(nowMillis + Math.max(0L, delay.toMillis()), seq++, action);
            tasks.add(task);
            lock.notifyAll();
        }
        return () -> {
            synchronized (lock) {
                tasks.remove(task);
            }
        };
    }

    /** Advances the clock; see the class documentation. */
    @Override
    public void sleep(Duration duration) {
        advance(duration);
    }

    /** Moves time forward by {@code duration}, running the timers that fall due. */
    public void advance(Duration duration) {
        if (duration.isNegative()) {
            throw new IllegalArgumentException("time only moves forward");
        }
        long target;
        synchronized (lock) {
            target = nowMillis + duration.toMillis();
        }
        while (true) {
            Task due;
            synchronized (lock) {
                due = tasks.peek();
                if (due == null || due.dueMillis() > target) {
                    nowMillis = Math.max(nowMillis, target);
                    return;
                }
                tasks.poll();
                nowMillis = Math.max(nowMillis, due.dueMillis());
            }
            due.action().run();
        }
    }

    /** Number of timers scheduled and not yet run or cancelled. */
    public int pendingTimers() {
        synchronized (lock) {
            return tasks.size();
        }
    }

    /**
     * Waits until at least {@code count} timers are pending.
     *
     * @param safety real-time limit, only to fail a broken test instead of hanging it
     * @return true if the timers are there, false if {@code safety} elapsed first
     */
    public boolean awaitTimers(int count, Duration safety) throws InterruptedException {
        long deadline = System.nanoTime() + safety.toNanos();
        synchronized (lock) {
            while (tasks.size() < count) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                lock.wait(Math.max(1L, left / 1_000_000L));
            }
            return true;
        }
    }
}

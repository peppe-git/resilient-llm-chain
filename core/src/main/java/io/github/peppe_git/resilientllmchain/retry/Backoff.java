// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.retry;

/**
 * Exponential backoff with multiplicative jitter.
 *
 * <p>Calls that fail together (a rate limit, a provider coming back) would otherwise retry together on
 * every doubling. Multiplying the deterministic wait by a factor in {@code [1 - spread, 1 + spread)}
 * keeps the mean wait while spreading the restarts; the spread grows with the wait, so the front does
 * not regroup after a few doublings.
 */
public final class Backoff {

    private Backoff() {
    }

    /**
     * Deterministic exponential wait for {@code attempt} (1-based): {@code base * 2^(attempt - 1)}.
     */
    public static long exponentialMillis(long baseMillis, int attempt) {
        if (baseMillis <= 0 || attempt <= 0) {
            return 0L;
        }
        int shift = Math.min(attempt - 1, 30);
        long value = baseMillis << shift;
        return value < 0 ? Long.MAX_VALUE : value;
    }

    /**
     * Applies jitter to a deterministic wait.
     *
     * @param deterministicMillis the wait before jitter; zero or negative stays zero
     * @param spread              half-width of the factor, in {@code [0, 1)}
     * @param random01            a random number in {@code [0, 1)}
     * @return a positive wait (never zero for a positive input, so a retry never becomes immediate)
     */
    public static long jitterMillis(long deterministicMillis, double spread, double random01) {
        if (deterministicMillis <= 0L) {
            return 0L;
        }
        double factor = (1.0 - spread) + random01 * (2.0 * spread);
        return Math.max(1L, Math.round(deterministicMillis * factor));
    }
}

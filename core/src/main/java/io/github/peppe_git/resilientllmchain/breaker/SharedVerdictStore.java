// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.breaker;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Shares the breaker's verdict ("stage X is cooling down for N more ms") across processes, so that an
 * outage is learnt once instead of once per process.
 *
 * <p>Only the verdict is shared; failure counters stay local. The verdict is a key whose time-to-live
 * <b>is</b> the cooldown: expiry is the mechanism, nothing needs cleaning up.
 *
 * <p><b>Best-effort by contract.</b> Implementations must not throw and must not block for long: on any
 * problem they answer "nothing known" ({@link Duration#ZERO}, {@code false}, empty) and the breaker
 * falls back to its local state. A breaker that failed because its cache failed would turn a cache
 * problem into an AI outage.
 */
public interface SharedVerdictStore {

    /** Publishes that {@code key} is open for {@code cooldown}. */
    void publishOpen(String key, Duration cooldown);

    /** Time left on the shared cooldown of {@code key}; zero when closed or unknown. */
    Duration remaining(String key);

    /** Removes the shared cooldown of {@code key}. False if the store could not be reached. */
    boolean clear(String key);

    /**
     * Publishes a manual reset of {@code key} at {@code at}. A missing cooldown key means either
     * "expired" or "reset", and only a reset must close a longer local cooldown opened before it.
     *
     * @param ttl how long the marker lives: the longest possible cooldown
     * @return false if the store could not be reached
     */
    boolean publishReset(String key, Instant at, Duration ttl);

    /** When {@code key} was last reset manually, if the marker is still alive. */
    Optional<Instant> lastReset(String key);
}

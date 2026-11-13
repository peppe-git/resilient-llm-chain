// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.breaker;

import io.github.peppe_git.resilientllmchain.time.ChainClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link SharedVerdictStore} inside one JVM: shares verdicts between several chains or breakers of
 * the same process, and stands in for Redis in tests. Expiry follows the given clock.
 */
public final class InMemorySharedVerdictStore implements SharedVerdictStore {

    private record Entry(String value, long expiresAtMillis) {
    }

    private final ChainClock clock;
    private final Map<String, Entry> opens = new ConcurrentHashMap<>();
    private final Map<String, Entry> resets = new ConcurrentHashMap<>();

    public InMemorySharedVerdictStore(ChainClock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void publishOpen(String key, Duration cooldown) {
        if (cooldown.isZero() || cooldown.isNegative()) {
            return;
        }
        opens.put(key, new Entry("1", clock.millis() + cooldown.toMillis()));
    }

    @Override
    public Duration remaining(String key) {
        Entry entry = alive(opens, key);
        return entry == null ? Duration.ZERO : Duration.ofMillis(entry.expiresAtMillis() - clock.millis());
    }

    @Override
    public boolean clear(String key) {
        opens.remove(key);
        return true;
    }

    @Override
    public boolean publishReset(String key, Instant at, Duration ttl) {
        resets.put(key, new Entry(Long.toString(at.toEpochMilli()), clock.millis() + Math.max(1L, ttl.toMillis())));
        return true;
    }

    @Override
    public Optional<Instant> lastReset(String key) {
        Entry entry = alive(resets, key);
        return entry == null ? Optional.empty() : Optional.of(Instant.ofEpochMilli(Long.parseLong(entry.value())));
    }

    private Entry alive(Map<String, Entry> map, String key) {
        Entry entry = map.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expiresAtMillis() <= clock.millis()) {
            map.remove(key, entry);
            return null;
        }
        return entry;
    }
}

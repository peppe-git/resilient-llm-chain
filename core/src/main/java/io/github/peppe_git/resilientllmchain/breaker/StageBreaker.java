// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.breaker;

import io.github.peppe_git.resilientllmchain.StageRef;
import io.github.peppe_git.resilientllmchain.time.ChainClock;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Circuit breaker per stage (provider and model). Thread-safe; one instance can serve several chains.
 *
 * <h2>Failures</h2>
 * Consecutive failures are counted per stage (a success resets the count). At {@code failureThreshold}
 * the stage opens for {@code cooldown}: {@link #isOpen} is true and the chain skips it. When the cooldown
 * expires the stage is half-open: the next call tries it, a success closes it and a failure reopens the
 * cooldown at once, because the count is still at the threshold. A permanent failure (the model is gone)
 * opens the long {@code permanentCooldown} immediately.
 *
 * <h2>Stalls</h2>
 * Counting completed failures cannot protect the first burst hit by a provider that accepts connections
 * and never answers: nothing completes until the timeouts expire. So a stage also counts as open while
 * at least {@code stallThreshold} of its calls have been in flight for {@code stallAfter} or more. The
 * condition is computed from the calls in flight, not stored: it ends as soon as those calls end, with
 * no cooldown to wait for. One slow call is slowness; several stuck together are a symptom.
 *
 * <h2>Shared verdict</h2>
 * With a {@link SharedVerdictStore}, opening a cooldown is published, and a cooldown opened by another
 * process is adopted (read at most every {@code sharedRefresh} per stage, never on every check). When the
 * two verdicts disagree the longer cooldown wins. A success clears the shared verdict; a manual
 * {@link #reset} also publishes a marker that closes cooldowns opened before it in every process. Every
 * store failure degrades to local-only behaviour.
 */
public final class StageBreaker {

    private static final System.Logger LOG = System.getLogger(StageBreaker.class.getName());
    private static final AtomicLong CALL_SEQ = new AtomicLong();

    /** Returned by {@link #begin}; must be closed when the call ends, in a {@code finally}. */
    @FunctionalInterface
    public interface CallHandle extends AutoCloseable {
        @Override
        void close();
    }

    private static final CallHandle NOOP = () -> { };

    private static final class State {
        final StageRef stage;
        final AtomicInteger consecutiveFailures = new AtomicInteger();
        volatile long openUntil;
        volatile String lastError;
        volatile long lastErrorAt;
        volatile long lastOpenedAt;
        /** When this process adopted a cooldown opened elsewhere; 0 = never. */
        volatile long adoptedAt;

        State(StageRef stage) {
            this.stage = stage;
        }
    }

    private final BreakerConfig config;
    private final ChainClock clock;
    private final SharedVerdictStore shared;
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Map<String, Map<Long, Long>> inFlight = new ConcurrentHashMap<>();
    private final Map<String, StageRef> refs = new ConcurrentHashMap<>();
    private final Map<String, Long> sharedCheckedAt = new ConcurrentHashMap<>();

    /**
     * @param config breaker settings
     * @param clock  time source
     * @param shared cross-process verdict store, or {@code null} for a local-only breaker
     */
    public StageBreaker(BreakerConfig config, ChainClock clock, SharedVerdictStore shared) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.shared = shared;
    }

    /** A breaker that shares nothing across processes. */
    public static StageBreaker local(BreakerConfig config, ChainClock clock) {
        return new StageBreaker(config, clock, null);
    }

    public BreakerConfig config() {
        return config;
    }

    /**
     * True if the chain should skip {@code stage}: cooldown open (locally or as adopted from the shared
     * verdict), or enough calls stalled in flight.
     */
    public boolean isOpen(StageRef stage) {
        if (!config.enabled()) {
            return false;
        }
        String key = stage.key();
        long now = clock.millis();
        // Read the shared verdict before the local state: the case that matters is the one where this
        // process has no local state yet because another process found the outage first.
        syncFromShared(stage, key, now);
        State s = states.get(key);
        if (s != null && s.openUntil > now) {
            return true;
        }
        return stalledCalls(key, now) >= config.stallThreshold();
    }

    /** Registers a call in flight. Close the handle in a {@code finally}: an unclosed call stalls forever. */
    public CallHandle begin(StageRef stage) {
        if (!config.enabled() || config.stallAfter().isZero()) {
            return NOOP;
        }
        String key = stage.key();
        refs.putIfAbsent(key, stage);
        Map<Long, Long> calls = inFlight.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
        long id = CALL_SEQ.incrementAndGet();
        calls.put(id, clock.millis());
        return () -> calls.remove(id);
    }

    /** A valid answer: resets the failure count and closes the breaker (half-open recovery). */
    public void recordSuccess(StageRef stage) {
        String key = stage.key();
        State s = states.get(key);
        if (s == null) {
            return;
        }
        boolean hadSomethingToClear = s.consecutiveFailures.get() > 0 || s.openUntil > 0 || s.adoptedAt > 0;
        s.consecutiveFailures.set(0);
        s.openUntil = 0L;
        s.adoptedAt = 0L;
        // Clear the shared verdict too, or the other processes keep skipping a recovered provider until the
        // key expires. Only when there was something to clear: a store round trip on every success would put
        // the cache on the hot path of every healthy call.
        if (shared != null && hadSomethingToClear) {
            safe(() -> shared.clear(key), false, "clear", key);
        }
    }

    /**
     * A failure after retries. Opens (or, when half-open, reopens) the cooldown once the count reaches
     * {@code failureThreshold}.
     *
     * @param error short description, kept for {@link #snapshot()}
     */
    public void recordFailure(StageRef stage, String error) {
        if (!config.enabled()) {
            return;
        }
        String key = stage.key();
        State s = states.computeIfAbsent(key, k -> new State(stage));
        long now = clock.millis();
        noteError(s, error, now);
        int failures = s.consecutiveFailures.incrementAndGet();
        if (failures >= config.failureThreshold()) {
            open(s, key, config.cooldown(), now);
            LOG.log(Level.WARNING, "stage {0} open for {1} ms after {2} consecutive failures",
                    stage, config.cooldown().toMillis(), failures);
        }
    }

    /**
     * A permanent failure (model not found, retired): retrying soon cannot help, so the long cooldown
     * opens at once, without waiting for the threshold.
     */
    public void recordPermanentFailure(StageRef stage, String error) {
        if (!config.enabled()) {
            return;
        }
        String key = stage.key();
        State s = states.computeIfAbsent(key, k -> new State(stage));
        long now = clock.millis();
        noteError(s, error, now);
        s.consecutiveFailures.updateAndGet(n -> Math.max(n + 1, config.failureThreshold()));
        open(s, key, config.permanentCooldown(), now);
        LOG.log(Level.WARNING, "stage {0} open for {1} ms after a permanent failure: {2}",
                stage, config.permanentCooldown().toMillis(), error);
    }

    /**
     * Manual reset ("try it now"): clears failures and cooldown here, and in every process sharing the
     * store. True if there was local state to clear.
     */
    public boolean reset(StageRef stage) {
        String key = stage.key();
        if (shared != null) {
            Duration markerTtl = config.cooldown().compareTo(config.permanentCooldown()) > 0
                    ? config.cooldown() : config.permanentCooldown();
            boolean cleared = safe(() -> shared.clear(key), false, "clear", key);
            boolean published = safe(() -> shared.publishReset(key, clock.instant(), markerTtl), false, "reset", key);
            if (!cleared || !published) {
                LOG.log(Level.WARNING, "manual reset of {0} not propagated: the shared store did not answer; "
                        + "other processes keep their cooldown until it expires", stage);
            }
        }
        return states.remove(key) != null;
    }

    /** Status of one stage now. */
    public BreakerStatus status(StageRef stage) {
        String key = stage.key();
        return statusOf(states.get(key), key, clock.millis());
    }

    /**
     * State of every stage that failed, adopted a shared cooldown, or has stalled calls. Stages that
     * never had a problem are not listed.
     */
    public List<BreakerSnapshot> snapshot() {
        if (!config.enabled()) {
            return List.of();
        }
        long now = clock.millis();
        Set<String> keys = new LinkedHashSet<>(states.keySet());
        inFlight.keySet().stream().filter(k -> stalledCalls(k, now) > 0).forEach(keys::add);
        List<BreakerSnapshot> out = new ArrayList<>();
        for (String key : keys) {
            State s = states.get(key);
            StageRef stage = s != null ? s.stage : refs.get(key);
            if (stage == null) {
                continue;
            }
            out.add(new BreakerSnapshot(
                    stage,
                    statusOf(s, key, now),
                    s == null ? 0 : s.consecutiveFailures.get(),
                    s == null ? null : s.lastError,
                    s == null ? null : instantOrNull(s.lastErrorAt),
                    s == null ? null : instantOrNull(s.openUntil),
                    stalledCalls(key, now),
                    s == null ? null : instantOrNull(s.lastOpenedAt)));
        }
        return out;
    }

    private BreakerStatus statusOf(State s, String key, long now) {
        if (!config.enabled()) {
            return BreakerStatus.CLOSED;
        }
        if (s != null && s.openUntil > now) {
            return BreakerStatus.OPEN;
        }
        // Stalls are checked after the explicit cooldown (the stronger signal, confirmed by completed
        // failures) and before declaring closed a stage that is ignoring its calls.
        if (stalledCalls(key, now) >= config.stallThreshold()) {
            return BreakerStatus.STALLED;
        }
        return s == null || s.openUntil == 0L ? BreakerStatus.CLOSED : BreakerStatus.HALF_OPEN;
    }

    private int stalledCalls(String key, long now) {
        long stallMillis = config.stallAfter().toMillis();
        if (stallMillis <= 0L) {
            return 0;
        }
        Map<Long, Long> calls = inFlight.get(key);
        if (calls == null || calls.isEmpty()) {
            return 0;
        }
        int stalled = 0;
        for (long startedAt : calls.values()) {
            if (now - startedAt >= stallMillis) {
                stalled++;
            }
        }
        return stalled;
    }

    private void open(State s, String key, Duration cooldown, long now) {
        s.openUntil = now + cooldown.toMillis();
        s.lastOpenedAt = now;
        if (shared != null) {
            safe(() -> {
                shared.publishOpen(key, cooldown);
                return true;
            }, false, "publish", key);
        }
    }

    private static void noteError(State s, String error, long now) {
        if (error != null) {
            s.lastError = error;
            s.lastErrorAt = now;
        }
    }

    /**
     * Brings into local state a cooldown opened by another process, at most every {@code sharedRefresh}
     * per stage. Local state is created only if the shared verdict is actually open, so a healthy stage
     * checked a thousand times leaves no trace. A longer local cooldown is never shortened: closing it
     * would send calls to a stage this process has just seen fail. A manual reset published after this
     * process opened or adopted its cooldown closes it.
     */
    private void syncFromShared(StageRef stage, String key, long now) {
        if (shared == null) {
            return;
        }
        Long last = sharedCheckedAt.get(key);
        if (last != null && now - last < config.sharedRefresh().toMillis()) {
            return;
        }
        sharedCheckedAt.put(key, now);
        long resetAt = safe(() -> shared.lastReset(key), Optional.<Instant>empty(), "read reset", key)
                .map(Instant::toEpochMilli).orElse(0L);
        State local = states.get(key);
        long seenOpen = local == null ? 0L : Math.max(local.lastOpenedAt, local.adoptedAt);
        if (resetAt > 0 && seenOpen > 0 && seenOpen <= resetAt) {
            states.remove(key, local);
        }
        long remaining = safe(() -> shared.remaining(key), Duration.ZERO, "read", key).toMillis();
        if (remaining <= 0) {
            return;
        }
        State s = states.computeIfAbsent(key, k -> new State(stage));
        if (now + remaining > s.openUntil) {
            s.openUntil = now + remaining;
            s.adoptedAt = now;
        }
    }

    private static <T> T safe(Supplier<T> call, T fallback, String operation, String key) {
        try {
            T value = call.get();
            return value == null ? fallback : value;
        } catch (RuntimeException e) {
            LOG.log(Level.DEBUG, "shared verdict store: {0} of {1} failed ({2}); local breaker only",
                    operation, key, e.toString());
            return fallback;
        }
    }

    private static Instant instantOrNull(long millis) {
        return millis == 0L ? null : Instant.ofEpochMilli(millis);
    }
}

// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.redis;

import io.github.peppe_git.resilientllmchain.breaker.SharedVerdictStore;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * {@link SharedVerdictStore} on Redis, through Lettuce.
 *
 * <ul>
 *   <li>An open verdict is {@code SET <prefix><key> 1 PX <cooldown>}: the key's time-to-live is the
 *       cooldown, so expiry needs no cleanup and does not depend on the processes' clocks.</li>
 *   <li>The remaining cooldown is {@code PTTL}; a success or a reset is {@code DEL}.</li>
 *   <li>A manual reset also writes {@code <prefix>reset:<key>} with its epoch milliseconds, living as long
 *       as the longest cooldown.</li>
 * </ul>
 *
 * <p>Every command has its own timeout ({@code commandTimeout}, default 250 ms), independent of the
 * connection's settings, and every failure (timeout, connection lost, unexpected reply) answers "nothing
 * known": the breaker falls back to its local state. Redis being slow must never slow a generation by
 * more than that timeout.
 *
 * <p>The connection is the caller's: this class never closes it. Lettuce connections are thread-safe and
 * can be shared with the rest of the application.
 */
public final class RedisSharedVerdictStore implements SharedVerdictStore {

    /** Default key prefix. Keys are {@code <prefix><provider>|<model>}. */
    public static final String DEFAULT_PREFIX = "rlc:breaker:";

    /** Default timeout of each command. */
    public static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofMillis(250);

    private static final System.Logger LOG = System.getLogger(RedisSharedVerdictStore.class.getName());

    private final RedisAsyncCommands<String, String> redis;
    private final String prefix;
    private final String resetPrefix;
    private final Duration commandTimeout;

    public RedisSharedVerdictStore(StatefulRedisConnection<String, String> connection) {
        this(connection, DEFAULT_PREFIX, DEFAULT_COMMAND_TIMEOUT);
    }

    /**
     * @param connection     a connection with string keys and values
     * @param prefix         namespace of the keys; give each environment its own
     * @param commandTimeout longest wait for one command
     */
    public RedisSharedVerdictStore(StatefulRedisConnection<String, String> connection, String prefix,
            Duration commandTimeout) {
        this.redis = Objects.requireNonNull(connection, "connection").async();
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        this.resetPrefix = prefix + "reset:";
        Objects.requireNonNull(commandTimeout, "commandTimeout");
        if (commandTimeout.isNegative() || commandTimeout.isZero()) {
            throw new IllegalArgumentException("commandTimeout must be positive");
        }
        this.commandTimeout = commandTimeout;
    }

    @Override
    public void publishOpen(String key, Duration cooldown) {
        if (cooldown.isZero() || cooldown.isNegative()) {
            return;
        }
        await(redis.set(prefix + key, "1", SetArgs.Builder.px(cooldown.toMillis())), null, "publish", key);
    }

    @Override
    public Duration remaining(String key) {
        Long ttl = await(redis.pttl(prefix + key), null, "read", key);
        return ttl == null || ttl <= 0 ? Duration.ZERO : Duration.ofMillis(ttl);
    }

    @Override
    public boolean clear(String key) {
        return await(redis.del(prefix + key), null, "clear", key) != null;
    }

    @Override
    public boolean publishReset(String key, Instant at, Duration ttl) {
        String reply = await(redis.set(resetPrefix + key, Long.toString(at.toEpochMilli()),
                SetArgs.Builder.px(Math.max(1L, ttl.toMillis()))), null, "reset", key);
        return reply != null;
    }

    @Override
    public Optional<Instant> lastReset(String key) {
        String value = await(redis.get(resetPrefix + key), null, "read reset", key);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.ofEpochMilli(Long.parseLong(value)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private <T> T await(RedisFuture<T> future, T fallback, String operation, String key) {
        try {
            return future.get(commandTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(false);
            return fallback;
        } catch (Exception e) {
            future.cancel(false);
            LOG.log(Level.DEBUG, "redis verdict store: {0} of {1} failed ({2}); local breaker only",
                    operation, key, e.toString());
            return fallback;
        }
    }
}

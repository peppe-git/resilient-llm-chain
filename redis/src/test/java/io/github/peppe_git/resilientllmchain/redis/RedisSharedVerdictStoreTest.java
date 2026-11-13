// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.redis;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.peppe_git.resilientllmchain.StageRef;
import io.github.peppe_git.resilientllmchain.breaker.BreakerConfig;
import io.github.peppe_git.resilientllmchain.breaker.StageBreaker;
import io.github.peppe_git.resilientllmchain.time.ManualChainClock;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
class RedisSharedVerdictStoreTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    private static RedisClient client;
    private static StatefulRedisConnection<String, String> connection;
    private static RedisCommands<String, String> sync;

    private String prefix;
    private RedisSharedVerdictStore store;

    @BeforeAll
    static void connect() {
        client = RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        connection = client.connect();
        sync = connection.sync();
    }

    @AfterAll
    static void disconnect() {
        connection.close();
        client.shutdown();
    }

    @BeforeEach
    void freshNamespace() {
        prefix = "test:" + UUID.randomUUID().toString().substring(0, 8) + ":";
        store = new RedisSharedVerdictStore(connection, prefix, Duration.ofSeconds(2));
    }

    @Test
    void anOpenVerdict_isAKeyWhoseTtlIsTheCooldown() {
        store.publishOpen("alpha|m", Duration.ofSeconds(90));

        assertThat(sync.get(prefix + "alpha|m")).isEqualTo("1");
        assertThat(store.remaining("alpha|m")).isBetween(Duration.ofSeconds(89), Duration.ofSeconds(90));
    }

    @Test
    void aZeroCooldown_writesNothing() {
        store.publishOpen("alpha|m", Duration.ZERO);

        assertThat(sync.exists(prefix + "alpha|m")).isZero();
    }

    @Test
    void anAbsentKey_meansClosed() {
        assertThat(store.remaining("never|opened")).isZero();
    }

    @Test
    void clear_removesTheVerdict() {
        store.publishOpen("alpha|m", Duration.ofSeconds(90));

        assertThat(store.clear("alpha|m")).isTrue();
        assertThat(store.remaining("alpha|m")).isZero();
    }

    @Test
    void theResetMarker_roundTrips_andExpires() {
        Instant at = Instant.ofEpochMilli(1_234_567_890_123L);

        assertThat(store.publishReset("alpha|m", at, Duration.ofHours(1))).isTrue();

        assertThat(store.lastReset("alpha|m")).contains(at);
        assertThat(sync.pttl(prefix + "reset:alpha|m")).isBetween(3_590_000L, 3_600_000L);
        assertThat(store.lastReset("beta|m")).isEmpty();
    }

    @Test
    void twoBreakers_shareTheVerdictThroughRedis() {
        ManualChainClock clock = new ManualChainClock();
        BreakerConfig config = BreakerConfig.builder().failureThreshold(1).cooldown(Duration.ofMinutes(5)).build();
        StageBreaker api = new StageBreaker(config, clock, store);
        StageBreaker worker = new StageBreaker(config,
                clock, new RedisSharedVerdictStore(connection, prefix, Duration.ofSeconds(2)));
        StageRef stage = new StageRef("alpha", "m");

        api.recordFailure(stage, "HTTP 503");

        assertThat(worker.isOpen(stage)).isTrue();
        clock.advance(Duration.ofSeconds(5));
        api.reset(stage);
        clock.advance(Duration.ofSeconds(5));
        assertThat(worker.isOpen(stage)).isFalse();
    }

    @Test
    void aLostConnection_answersNothingKnown_insteadOfThrowing() {
        StatefulRedisConnection<String, String> lost = client.connect();
        RedisSharedVerdictStore broken = new RedisSharedVerdictStore(lost, prefix, Duration.ofMillis(200));
        lost.close();

        broken.publishOpen("alpha|m", Duration.ofSeconds(90));
        assertThat(broken.remaining("alpha|m")).isZero();
        assertThat(broken.clear("alpha|m")).isFalse();
        assertThat(broken.publishReset("alpha|m", Instant.now(), Duration.ofMinutes(1))).isFalse();
        assertThat(broken.lastReset("alpha|m")).isEmpty();
    }

    @Test
    void aStalledRedis_costsAtMostTheCommandTimeout() {
        RedisSharedVerdictStore impatient = new RedisSharedVerdictStore(connection, prefix, Duration.ofMillis(50));
        StatefulRedisConnection<String, String> admin = client.connect();
        try {
            admin.sync().clientPause(500);
            long start = System.nanoTime();

            assertThat(impatient.remaining("alpha|m")).isZero();

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(450));
        } finally {
            admin.close();
        }
    }
}

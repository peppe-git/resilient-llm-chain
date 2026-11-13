// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.peppe_git.resilientllmchain.ChainSaturatedException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ConcurrencyLimiterTest {

    @Test
    void holdsAPermitForTheDurationOfTheBody() {
        ConcurrencyLimiter limiter = ConcurrencyLimiter.of(2, Duration.ZERO);

        int inside = limiter.call(false, limiter::availablePermits);

        assertThat(inside).isEqualTo(1);
        assertThat(limiter.availablePermits()).isEqualTo(2);
    }

    @Test
    void releasesThePermit_evenWhenTheBodyThrows() {
        ConcurrencyLimiter limiter = ConcurrencyLimiter.of(1, Duration.ZERO);

        assertThatThrownBy(() -> limiter.call(false, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(limiter.availablePermits()).isEqualTo(1);
    }

    @Test
    void anInteractiveCall_failsFast_whenNoPermitFreesUpInTime() throws Exception {
        ConcurrencyLimiter limiter = ConcurrencyLimiter.of(1, Duration.ZERO);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> limiter.call(false, () -> {
            holding.countDown();
            await(release);
            return null;
        }));
        assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> limiter.call(true, () -> "never")).isInstanceOf(ChainSaturatedException.class);
        release.countDown();
    }

    @Test
    void aBackgroundCall_waitsForThePermit() throws Exception {
        ConcurrencyLimiter limiter = ConcurrencyLimiter.of(1, Duration.ZERO);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> limiter.call(false, () -> {
            holding.countDown();
            await(release);
            return null;
        }));
        assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<String> waiting = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> waiting.complete(limiter.call(false, () -> "served")));
        assertThat(waiting).isNotDone();
        release.countDown();

        assertThat(waiting.get(5, TimeUnit.SECONDS)).isEqualTo("served");
        assertThat(limiter.waits()).isEqualTo(2);
    }

    @Test
    void unbounded_neverThrottles() {
        assertThat(ConcurrencyLimiter.unbounded().availablePermits()).isEqualTo(Integer.MAX_VALUE);
        assertThat(ConcurrencyLimiter.unbounded().call(true, () -> "ok")).isEqualTo("ok");
        assertThatThrownBy(() -> ConcurrencyLimiter.of(0, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

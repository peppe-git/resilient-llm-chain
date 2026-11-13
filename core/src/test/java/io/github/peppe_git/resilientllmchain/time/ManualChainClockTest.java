// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ManualChainClockTest {

    private final ManualChainClock clock = new ManualChainClock(Instant.ofEpochSecond(1_000));

    @Test
    void timeMovesOnlyWhenAdvanced() {
        assertThat(clock.instant()).isEqualTo(Instant.ofEpochSecond(1_000));
        clock.advance(Duration.ofSeconds(5));
        assertThat(clock.millis()).isEqualTo(1_005_000);
        assertThatThrownBy(() -> clock.advance(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dueTimersRun_inDueOrder_withTheClockAtTheirDueTime() {
        List<String> runs = new CopyOnWriteArrayList<>();
        clock.schedule(Duration.ofSeconds(3), () -> runs.add("c@" + clock.millis()));
        clock.schedule(Duration.ofSeconds(1), () -> runs.add("a@" + clock.millis()));
        clock.schedule(Duration.ofSeconds(1), () -> runs.add("b@" + clock.millis()));
        clock.schedule(Duration.ofSeconds(9), () -> runs.add("never"));

        clock.advance(Duration.ofSeconds(5));

        assertThat(runs).containsExactly("a@1001000", "b@1001000", "c@1003000");
        assertThat(clock.millis()).isEqualTo(1_005_000);
        assertThat(clock.pendingTimers()).isEqualTo(1);
    }

    @Test
    void aCancelledTimer_neverRuns() {
        List<String> runs = new CopyOnWriteArrayList<>();
        ChainClock.Timer timer = clock.schedule(Duration.ofSeconds(1), () -> runs.add("x"));

        timer.cancel();
        clock.advance(Duration.ofSeconds(2));

        assertThat(runs).isEmpty();
        assertThat(clock.pendingTimers()).isZero();
    }

    @Test
    void aTimerScheduledByATimer_runsInTheSameAdvance_ifDue() {
        List<String> runs = new CopyOnWriteArrayList<>();
        clock.schedule(Duration.ofSeconds(1), () ->
                clock.schedule(Duration.ofSeconds(1), () -> runs.add("second")));

        clock.advance(Duration.ofSeconds(2));

        assertThat(runs).containsExactly("second");
    }

    @Test
    void sleep_advancesTheClock_withoutBlocking() {
        clock.sleep(Duration.ofMillis(1_500));

        assertThat(clock.millis()).isEqualTo(1_001_500);
    }

    @Test
    void awaitTimers_waitsForAnotherThreadToSchedule() throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            try {
                go.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            clock.schedule(Duration.ofSeconds(1), () -> { });
        });

        assertThat(clock.awaitTimers(1, Duration.ofMillis(1))).isFalse();
        go.countDown();
        assertThat(clock.awaitTimers(1, Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void asClock_readsTheSameInstant() {
        assertThat(clock.asClock().instant()).isEqualTo(clock.instant());
        assertThat(ChainClock.system().asClock().getZone()).isNotNull();
    }

    @Test
    void theSystemClock_runsAnImmediateTimer() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);
        ChainClock.system().schedule(Duration.ZERO, ran::countDown);

        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
    }
}

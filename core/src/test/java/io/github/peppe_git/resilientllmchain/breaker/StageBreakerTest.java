// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.breaker;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.peppe_git.resilientllmchain.StageRef;
import io.github.peppe_git.resilientllmchain.time.ManualChainClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class StageBreakerTest {

    private static final StageRef STAGE = new StageRef("alpha", "model-a");
    private static final Duration COOLDOWN = Duration.ofSeconds(90);
    private static final Duration PERMANENT = Duration.ofHours(6);

    private final ManualChainClock clock = new ManualChainClock();

    private StageBreaker breaker(boolean enabled) {
        return StageBreaker.local(BreakerConfig.builder()
                .enabled(enabled)
                .failureThreshold(3)
                .cooldown(COOLDOWN)
                .permanentCooldown(PERMANENT)
                .build(), clock);
    }

    @Test
    void staysClosed_belowTheThreshold() {
        StageBreaker breaker = breaker(true);
        breaker.recordFailure(STAGE, "e1");
        breaker.recordFailure(STAGE, "e2");

        assertThat(breaker.isOpen(STAGE)).isFalse();
        assertThat(breaker.status(STAGE)).isEqualTo(BreakerStatus.CLOSED);
    }

    @Test
    void opensAtTheThreshold_forTheCooldown() {
        StageBreaker breaker = breaker(true);
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure(STAGE, "e" + i);
        }

        assertThat(breaker.isOpen(STAGE)).isTrue();
        clock.advance(COOLDOWN.minusMillis(1));
        assertThat(breaker.isOpen(STAGE)).isTrue();
        clock.advance(Duration.ofMillis(1));
        assertThat(breaker.isOpen(STAGE)).isFalse();
        assertThat(breaker.status(STAGE)).isEqualTo(BreakerStatus.HALF_OPEN);
    }

    @Test
    void aSuccess_closesTheBreaker_andResetsTheCount() {
        StageBreaker breaker = breaker(true);
        breaker.recordFailure(STAGE, "e");
        breaker.recordFailure(STAGE, "e");
        breaker.recordSuccess(STAGE);
        breaker.recordFailure(STAGE, "e");
        breaker.recordFailure(STAGE, "e");

        assertThat(breaker.isOpen(STAGE)).isFalse();
    }

    @Test
    void aFailureWhileHalfOpen_reopensAtOnce() {
        StageBreaker breaker = breaker(true);
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure(STAGE, "e");
        }
        clock.advance(COOLDOWN);
        assertThat(breaker.isOpen(STAGE)).isFalse();

        breaker.recordFailure(STAGE, "still down");

        assertThat(breaker.isOpen(STAGE)).isTrue();
    }

    @Test
    void disabled_neverOpens_andReportsNothing() {
        StageBreaker breaker = breaker(false);
        for (int i = 0; i < 10; i++) {
            breaker.recordFailure(STAGE, "e");
        }
        breaker.recordPermanentFailure(STAGE, "gone");

        assertThat(breaker.isOpen(STAGE)).isFalse();
        assertThat(breaker.snapshot()).isEmpty();
    }

    @Test
    void stagesAreIndependent_perProviderAndModel() {
        StageBreaker breaker = breaker(true);
        StageRef sameProviderOtherModel = new StageRef("alpha", "model-b");
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure(STAGE, "e");
        }

        assertThat(breaker.isOpen(STAGE)).isTrue();
        assertThat(breaker.isOpen(sameProviderOtherModel)).isFalse();
        assertThat(breaker.isOpen(new StageRef("beta", "model-a"))).isFalse();
    }

    @Test
    void permanentFailure_opensTheLongCooldown_atOnce() {
        StageBreaker breaker = breaker(true);
        breaker.recordPermanentFailure(STAGE, "HTTP 404");

        assertThat(breaker.isOpen(STAGE)).isTrue();
        clock.advance(COOLDOWN.multipliedBy(10));
        assertThat(breaker.isOpen(STAGE)).as("still within the long cooldown").isTrue();
        clock.advance(PERMANENT);
        assertThat(breaker.isOpen(STAGE)).isFalse();
    }

    @Test
    void permanentFailure_isLiftedByASuccess() {
        StageBreaker breaker = breaker(true);
        breaker.recordPermanentFailure(STAGE, "HTTP 410");

        breaker.recordSuccess(STAGE);

        assertThat(breaker.isOpen(STAGE)).isFalse();
        assertThat(breaker.status(STAGE)).isEqualTo(BreakerStatus.CLOSED);
    }

    @Test
    void snapshot_reportsStatusErrorAndTimes() {
        StageBreaker breaker = breaker(true);
        long start = clock.millis();
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure(STAGE, "HTTP 503: overloaded");
        }

        BreakerSnapshot snapshot = breaker.snapshot().getFirst();

        assertThat(snapshot.stage()).isEqualTo(STAGE);
        assertThat(snapshot.status()).isEqualTo(BreakerStatus.OPEN);
        assertThat(snapshot.consecutiveFailures()).isEqualTo(3);
        assertThat(snapshot.lastError()).isEqualTo("HTTP 503: overloaded");
        assertThat(snapshot.openUntil().toEpochMilli()).isEqualTo(start + COOLDOWN.toMillis());
        assertThat(snapshot.lastOpenedAt().toEpochMilli()).isEqualTo(start);

        clock.advance(COOLDOWN);
        assertThat(breaker.snapshot().getFirst().status()).isEqualTo(BreakerStatus.HALF_OPEN);
    }

    @Test
    void healthyStages_doNotAppearInTheSnapshot() {
        StageBreaker breaker = breaker(true);
        breaker.recordSuccess(STAGE);
        assertThat(breaker.isOpen(STAGE)).isFalse();

        assertThat(breaker.snapshot()).isEmpty();
    }

    @Test
    void reset_clearsTheStage() {
        StageBreaker breaker = breaker(true);
        breaker.recordPermanentFailure(STAGE, "gone");

        assertThat(breaker.reset(STAGE)).isTrue();
        assertThat(breaker.isOpen(STAGE)).isFalse();
        assertThat(breaker.reset(STAGE)).as("nothing left to clear").isFalse();
    }
}

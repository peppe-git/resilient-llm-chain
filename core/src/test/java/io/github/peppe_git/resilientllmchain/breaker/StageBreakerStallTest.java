// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.breaker;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.peppe_git.resilientllmchain.StageRef;
import io.github.peppe_git.resilientllmchain.time.ManualChainClock;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Stall detection: calls stuck in flight open the stage before any failure completes. */
class StageBreakerStallTest {

    private static final StageRef STAGE = new StageRef("alpha", "model-a");
    private static final Duration STALL = Duration.ofSeconds(30);

    private final ManualChainClock clock = new ManualChainClock();
    private final StageBreaker breaker = StageBreaker.local(BreakerConfig.builder()
            .stallAfter(STALL)
            .stallThreshold(2)
            .cooldown(Duration.ofSeconds(90))
            .build(), clock);

    @Test
    void callsStuckBeyondTheThreshold_openTheStage_beforeAnyFailureCompletes() {
        breaker.begin(STAGE);
        breaker.begin(STAGE);

        clock.advance(STALL.minusMillis(1));
        assertThat(breaker.isOpen(STAGE)).isFalse();

        clock.advance(Duration.ofMillis(1));
        assertThat(breaker.isOpen(STAGE)).isTrue();
        assertThat(breaker.status(STAGE)).isEqualTo(BreakerStatus.STALLED);
        assertThat(breaker.snapshot().getFirst().stalledCalls()).isEqualTo(2);
    }

    @Test
    void oneSlowCall_isNotAStall() {
        breaker.begin(STAGE);
        clock.advance(STALL.multipliedBy(10));

        assertThat(breaker.isOpen(STAGE)).isFalse();
    }

    @Test
    void callsStartedAtDifferentTimes_countOnlyOnceTheyAreOldEnough() {
        breaker.begin(STAGE);
        clock.advance(STALL.dividedBy(2));
        breaker.begin(STAGE);
        clock.advance(STALL.dividedBy(2));
        assertThat(breaker.isOpen(STAGE)).as("only the first call is old enough").isFalse();

        clock.advance(STALL.dividedBy(2));
        assertThat(breaker.isOpen(STAGE)).isTrue();
    }

    @Test
    void endingTheStalledCalls_closesTheStage_withNoCooldownToWaitFor() {
        StageBreaker.CallHandle first = breaker.begin(STAGE);
        StageBreaker.CallHandle second = breaker.begin(STAGE);
        clock.advance(STALL);
        assertThat(breaker.isOpen(STAGE)).isTrue();

        first.close();

        assertThat(breaker.isOpen(STAGE)).isFalse();
        assertThat(breaker.status(STAGE)).isEqualTo(BreakerStatus.CLOSED);
        second.close();
        second.close();
    }

    @Test
    void stallsArePerStage() {
        breaker.begin(STAGE);
        breaker.begin(new StageRef("alpha", "model-b"));
        clock.advance(STALL);

        assertThat(breaker.isOpen(STAGE)).isFalse();
        assertThat(breaker.isOpen(new StageRef("alpha", "model-b"))).isFalse();
    }

    @Test
    void zeroStallAfter_disablesDetection() {
        StageBreaker noStalls = StageBreaker.local(BreakerConfig.builder().stallAfter(Duration.ZERO).build(), clock);
        noStalls.begin(STAGE);
        noStalls.begin(STAGE);
        noStalls.begin(STAGE);
        clock.advance(Duration.ofHours(1));

        assertThat(noStalls.isOpen(STAGE)).isFalse();
    }

    @Test
    void anExplicitCooldown_winsOverTheStallInTheStatus() {
        breaker.begin(STAGE);
        breaker.begin(STAGE);
        clock.advance(STALL);
        breaker.recordPermanentFailure(STAGE, "gone");

        assertThat(breaker.status(STAGE)).isEqualTo(BreakerStatus.OPEN);
    }
}

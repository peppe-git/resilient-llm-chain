// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    void backoffDoubles_atEachAttempt() {
        RetryPolicy policy = RetryPolicy.builder().random(() -> 0.5).baseDelay(Duration.ofMillis(400)).build();

        assertThat(policy.delayBeforeRetry(1, null)).isEqualTo(Duration.ofMillis(400));
        assertThat(policy.delayBeforeRetry(2, null)).isEqualTo(Duration.ofMillis(800));
        assertThat(policy.delayBeforeRetry(3, null)).isEqualTo(Duration.ofMillis(1_600));
    }

    @Test
    void jitter_spreadsTheWait_withinItsBounds() {
        RetryPolicy low = RetryPolicy.builder().random(() -> 0.0).baseDelay(Duration.ofMillis(400)).build();
        RetryPolicy high = RetryPolicy.builder().random(() -> 0.999_999).baseDelay(Duration.ofMillis(400)).build();

        assertThat(low.delayBeforeRetry(1, null)).isEqualTo(Duration.ofMillis(200));
        assertThat(high.delayBeforeRetry(1, null)).isEqualTo(Duration.ofMillis(600));
    }

    @Test
    void theCap_isAppliedAfterJitter_soItStaysAnAbsoluteMaximum() {
        RetryPolicy policy = RetryPolicy.builder().random(() -> 0.999_999)
                .baseDelay(Duration.ofSeconds(4)).maxDelay(Duration.ofSeconds(5)).build();

        assertThat(policy.delayBeforeRetry(1, null)).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.delayBeforeRetry(30, null)).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void retryAfter_isExact_andCapped() {
        RetryPolicy policy = RetryPolicy.builder().random(() -> 0.0).maxRetryAfter(Duration.ofSeconds(5)).build();

        assertThat(policy.delayBeforeRetry(1, Duration.ofSeconds(2))).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delayBeforeRetry(1, Duration.ofMinutes(5))).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void none_meansASingleCall() {
        assertThat(RetryPolicy.none().maxAttempts()).isEqualTo(1);
        assertThat(RetryPolicy.defaults().maxAttempts()).isEqualTo(3);
    }

    @Test
    void invalidSettings_areRejected() {
        assertThatThrownBy(() -> RetryPolicy.builder().maxAttempts(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetryPolicy.builder().jitterSpread(1.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetryPolicy.builder().baseDelay(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void backoffHelpers_handleEdgeCases() {
        assertThat(Backoff.jitterMillis(0, 0.5, 0.3)).isZero();
        assertThat(Backoff.jitterMillis(1, 0.5, 0.0)).as("never an immediate retry").isEqualTo(1);
        assertThat(Backoff.exponentialMillis(1_000, 64)).isPositive();
        assertThat(Backoff.exponentialMillis(0, 3)).isZero();
    }
}

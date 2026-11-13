// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.hedge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.peppe_git.resilientllmchain.cost.ModelPrice;
import java.time.Duration;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class HedgePolicyTest {

    @Test
    void disabledPolicy_hasNoDelay_andIsNotEnabled() {
        HedgePolicy policy = HedgePolicy.disabled();

        assertThat(policy.isEnabled()).isFalse();
        assertThat(policy.delay(false)).isZero();
        assertThat(policy.delay(true)).isZero();
    }

    @Test
    void after_usesTheSameDelayForBothKindsOfCall() {
        HedgePolicy policy = HedgePolicy.after(Duration.ofSeconds(7));

        assertThat(policy.isEnabled()).isTrue();
        assertThat(policy.delay(false)).isEqualTo(Duration.ofSeconds(7));
        assertThat(policy.delay(true)).isEqualTo(Duration.ofSeconds(7));
        assertThat(policy.maxCostRatio()).isEqualTo(2.0);
    }

    @Test
    void interactiveCalls_useTheirShorterDelay() {
        HedgePolicy policy = HedgePolicy.builder()
                .backgroundDelay(Duration.ofSeconds(20))
                .interactiveDelay(Duration.ofSeconds(5))
                .build();

        assertThat(policy.delay(false)).isEqualTo(Duration.ofSeconds(20));
        assertThat(policy.delay(true)).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void interactiveDelay_isNeverLongerThanTheBackgroundOne() {
        // A person waiting must never wait longer than work nobody watches: a misconfiguration is clamped.
        HedgePolicy policy = HedgePolicy.builder()
                .backgroundDelay(Duration.ofSeconds(10))
                .interactiveDelay(Duration.ofSeconds(30))
                .build();

        assertThat(policy.delay(true)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void interactiveDelay_defaultsToTheBackgroundOne() {
        HedgePolicy policy = HedgePolicy.builder().backgroundDelay(Duration.ofSeconds(12)).build();

        assertThat(policy.delay(true)).isEqualTo(Duration.ofSeconds(12));
    }

    @Test
    void zeroInteractiveDelay_turnsHedgingOffForInteractiveCallsOnly() {
        HedgePolicy policy = HedgePolicy.builder()
                .backgroundDelay(Duration.ofSeconds(10))
                .interactiveDelay(Duration.ZERO)
                .build();

        assertThat(policy.isEnabled()).isTrue();
        assertThat(policy.delay(true)).isZero();
        assertThat(policy.delay(false)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void zeroBackgroundDelay_disablesEverything() {
        HedgePolicy policy = HedgePolicy.builder()
                .backgroundDelay(Duration.ZERO)
                .interactiveDelay(Duration.ofSeconds(3))
                .build();

        assertThat(policy.isEnabled()).isFalse();
        assertThat(policy.delay(true)).isZero();
    }

    @Test
    void negativeDelays_areRejected() {
        assertThatThrownBy(() -> HedgePolicy.builder().backgroundDelay(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HedgePolicy.builder().interactiveDelay(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void basis_isThePriceOfTheConfiguredBasket() {
        ModelPrice price = ModelPrice.perMillion(1.0, 3.0);

        HedgePolicy oneToOne = HedgePolicy.after(Duration.ofSeconds(1));
        assertThat(oneToOne.basis(price).getAsDouble()).isEqualTo(4.0);

        HedgePolicy inputHeavy = HedgePolicy.builder()
                .backgroundDelay(Duration.ofSeconds(1))
                .costBasket(4_000_000, 1_000_000)
                .build();
        assertThat(inputHeavy.basis(price).getAsDouble()).isEqualTo(7.0);
    }

    @Test
    void basis_ofAnUnknownOrFreePrice_isEmpty() {
        HedgePolicy policy = HedgePolicy.after(Duration.ofSeconds(1));

        assertThat(policy.basis(null)).isEmpty();
        assertThat(policy.basis(ModelPrice.perMillion(0, 0))).isEmpty();
    }

    @Test
    void costCap_blocksACandidateDearerThanTheRatio() {
        HedgePolicy policy = HedgePolicy.builder()
                .backgroundDelay(Duration.ofSeconds(1))
                .maxCostRatio(2.0)
                .build();

        assertThat(policy.allowsCost(OptionalDouble.of(1.0), OptionalDouble.of(2.0))).isTrue();
        assertThat(policy.allowsCost(OptionalDouble.of(1.0), OptionalDouble.of(2.01))).isFalse();
        assertThat(policy.allowsCost(OptionalDouble.of(1.0), OptionalDouble.of(0.1))).isTrue();
    }

    @Test
    void costCap_staysSilentWhenAPriceIsUnknown() {
        // An unknown price is not zero: treating it as zero would block every hedge from a free primary.
        HedgePolicy policy = HedgePolicy.after(Duration.ofSeconds(1));

        assertThat(policy.allowsCost(OptionalDouble.empty(), OptionalDouble.of(100.0))).isTrue();
        assertThat(policy.allowsCost(OptionalDouble.of(1.0), OptionalDouble.empty())).isTrue();
    }

    @Test
    void nonPositiveRatio_disablesTheCostCap() {
        HedgePolicy policy = HedgePolicy.builder()
                .backgroundDelay(Duration.ofSeconds(1))
                .maxCostRatio(0)
                .build();

        assertThat(policy.allowsCost(OptionalDouble.of(1.0), OptionalDouble.of(1_000.0))).isTrue();
    }

    @Test
    void emptyBasket_isRejected() {
        assertThatThrownBy(() -> HedgePolicy.builder().costBasket(0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

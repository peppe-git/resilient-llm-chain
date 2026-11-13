// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.peppe_git.resilientllmchain.Prompt;
import io.github.peppe_git.resilientllmchain.StageRef;
import io.github.peppe_git.resilientllmchain.Usage;
import org.junit.jupiter.api.Test;

class CostTest {

    @Test
    void aPrice_costsEveryKindOfToken() {
        ModelPrice price = new ModelPrice(2.0, 8.0, 0.5, 2.5);

        double cost = price.cost(new Usage(1_000_000, 500_000, 2_000_000, 400_000));

        assertThat(cost).isEqualTo(2.0 + 4.0 + 1.0 + 1.0);
        assertThat(ModelPrice.perMillion(0, 0).isFree()).isTrue();
        assertThatThrownBy(() -> ModelPrice.perMillion(-1, 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theTable_prefersTheExactModel_thenTheProviderDefault() {
        PriceTable table = PriceTable.builder()
                .price("Alpha", "large", ModelPrice.perMillion(10, 10))
                .providerDefault("alpha", ModelPrice.perMillion(1, 1))
                .build();

        assertThat(table.price(new StageRef("alpha", "large"))).contains(ModelPrice.perMillion(10, 10));
        assertThat(table.price(new StageRef("alpha", "small"))).contains(ModelPrice.perMillion(1, 1));
        assertThat(table.price(new StageRef("beta", "large"))).isEmpty();
        assertThat(PriceTable.none().price(new StageRef("alpha", "large"))).isEmpty();
    }

    @Test
    void theCharacterHeuristic_estimatesInputFromTheRequestSize() {
        AbandonedSpendEstimator<Prompt> estimator = AbandonedSpendEstimator.characterHeuristic(Prompt::characters, 2_048);

        Usage usage = estimator.estimate(new Prompt("12345678", "1234"), new StageRef("p", "m"), new RuntimeException());

        assertThat(usage).isEqualTo(Usage.of(3, 2_048));
        assertThat(AbandonedSpendEstimator.<Prompt>none().estimate(null, null, null)).isEqualTo(Usage.ZERO);
    }
}

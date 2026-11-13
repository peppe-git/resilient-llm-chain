// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChainSpecTest {

    @Test
    void parsesProvidersAndPinnedModels() {
        ChainSpec spec = ChainSpec.parse(" Alpha:model-x , beta,gamma:model-y ");

        assertThat(spec.entries()).containsExactly(
                new ChainSpec.Entry("alpha", "model-x"),
                new ChainSpec.Entry("beta", null),
                new ChainSpec.Entry("gamma", "model-y"));
    }

    @Test
    void everythingAfterTheFirstColon_isTheModel() {
        ChainSpec spec = ChainSpec.parse("local:family:7b-q4");

        assertThat(spec.entries()).containsExactly(new ChainSpec.Entry("local", "family:7b-q4"));
    }

    @Test
    void blankEntriesAndBlankModels_areIgnored() {
        ChainSpec spec = ChainSpec.parse(",, alpha: ,, :model,beta,");

        assertThat(spec.entries()).containsExactly(new ChainSpec.Entry("alpha", null), new ChainSpec.Entry("beta", null));
    }

    @Test
    void duplicates_keepTheFirstOccurrence() {
        ChainSpec spec = ChainSpec.parse("alpha,beta:m,ALPHA,beta:m,beta:n");

        assertThat(spec).hasToString("alpha,beta:m,beta:n");
    }

    @Test
    void nullOrBlank_isAnEmptyChain() {
        assertThat(ChainSpec.parse(null).isEmpty()).isTrue();
        assertThat(ChainSpec.parse("  ").isEmpty()).isTrue();
    }

    @Test
    void toString_roundTrips() {
        ChainSpec spec = ChainSpec.parse("a:m1,b,c:x:y");

        assertThat(ChainSpec.parse(spec.toString())).isEqualTo(spec);
        assertThat(ChainSpec.ofProviders("a", "b")).isEqualTo(ChainSpec.parse("a,b"));
    }
}

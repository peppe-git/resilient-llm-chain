// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.peppe_git.resilientllmchain.error.OutputRejectedException;
import io.github.peppe_git.resilientllmchain.event.UsageEvent;
import io.github.peppe_git.resilientllmchain.retry.RetryPolicy;
import io.github.peppe_git.resilientllmchain.time.ManualChainClock;
import java.net.ConnectException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Output rejections: a failed stage for the chain, neutral for the breaker, and a stop after two in a row. */
class ChainRejectionTest {

    private static final Prompt PROMPT = Prompt.of("hello");
    private static final OutputValidator NO_BAD_WORDS =
            result -> result.text().contains("bad") ? Optional.of("contains 'bad'") : Optional.empty();

    private final Recorder recorder = new Recorder();

    private ResilientChain<Prompt> chain(FakeProvider... providers) {
        ResilientChain.Builder<Prompt> builder = ResilientChain.<Prompt>builder()
                .clock(new ManualChainClock())
                .retry(RetryPolicy.none())
                .outputValidator(NO_BAD_WORDS)
                .usageListener(recorder)
                .eventListener(recorder);
        for (FakeProvider provider : providers) {
            builder.provider(provider);
        }
        return builder.build();
    }

    @Test
    void twoConsecutiveRejections_stopTheChainBeforeTheRemainingStages() {
        FakeProvider a = new FakeProvider("a").thenAnswer("bad one");
        FakeProvider b = new FakeProvider("b").thenAnswer("bad two");
        FakeProvider c = new FakeProvider("c").thenAnswer("good");
        ResilientChain<Prompt> chain = chain(a, b, c);

        assertThatThrownBy(() -> chain.execute(PROMPT))
                .isInstanceOf(ChainExhaustedException.class)
                .cause().isInstanceOf(OutputRejectedException.class).hasMessageContaining("contains 'bad'");
        assertThat(c.calls()).isZero();
        assertThat(recorder.stoppedByRejections).isEqualTo(1);
        assertThat(chain.breaker().snapshot()).as("the providers are up").isEmpty();
    }

    @Test
    void oneRejection_stopsNothing_theNextStageServes() {
        FakeProvider a = new FakeProvider("a").thenAnswer("bad");
        FakeProvider b = new FakeProvider("b").thenAnswer("good");
        ResilientChain<Prompt> chain = chain(a, b);

        assertThat(chain.execute(PROMPT).text()).isEqualTo("good");
        UsageEvent rejected = recorder.usage.getFirst();
        assertThat(rejected.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(rejected.usage()).as("a rejected answer was paid for").isEqualTo(Usage.of(100, 50));
    }

    @Test
    void aNetworkFailureInBetween_resetsTheCount() {
        FakeProvider a = new FakeProvider("a").thenAnswer("bad");
        FakeProvider b = new FakeProvider("b").thenFail(new ConnectException("refused"));
        FakeProvider c = new FakeProvider("c").thenAnswer("bad again");
        FakeProvider d = new FakeProvider("d").thenAnswer("good");
        ResilientChain<Prompt> chain = chain(a, b, c, d);

        assertThat(chain.execute(PROMPT).text()).isEqualTo("good");
        assertThat(recorder.stoppedByRejections).isZero();
    }

    @Test
    void aPerCallValidator_replacesTheChainOne() {
        FakeProvider a = new FakeProvider("a").thenAnswer("bad but fine here");
        ResilientChain<Prompt> chain = chain(a);

        ChainResult result = chain.execute(ChainSpec.ofProviders("a"), PROMPT,
                CallOptions.background().withValidator(OutputValidator.acceptAll()));

        assertThat(result.text()).isEqualTo("bad but fine here");
    }

    @Test
    void anUnpinnedStageThatFails_declaresItsProvidersDefaultModel() {
        FakeProvider a = new FakeProvider("a", "a-default", List.of())
                .thenFail(new ConnectException("refused"));
        ResilientChain<Prompt> chain = chain(a, new FakeProvider("b"));

        chain.execute(ChainSpec.parse("a,b"), PROMPT, CallOptions.background());

        assertThat(recorder.usage.getFirst().stage()).isEqualTo(new StageRef("a", "a-default"));
    }

    @Test
    void aPinnedStageThatFails_declaresThePinnedModel() {
        FakeProvider a = new FakeProvider("a", "a-default", List.of())
                .thenFail(new ConnectException("refused"));
        ResilientChain<Prompt> chain = chain(a, new FakeProvider("b"));

        chain.execute(ChainSpec.parse("a:a-pinned,b"), PROMPT, CallOptions.background());

        assertThat(a.calledModels()).containsExactly("a-pinned");
        assertThat(recorder.usage.getFirst().stage()).isEqualTo(new StageRef("a", "a-pinned"));
    }
}

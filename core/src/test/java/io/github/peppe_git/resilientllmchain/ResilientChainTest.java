// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.peppe_git.resilientllmchain.error.EmptyOutputException;
import io.github.peppe_git.resilientllmchain.error.ProviderHttpException;
import io.github.peppe_git.resilientllmchain.event.ChainEventListener;
import io.github.peppe_git.resilientllmchain.event.SkipReason;
import io.github.peppe_git.resilientllmchain.retry.RetryPolicy;
import io.github.peppe_git.resilientllmchain.time.ManualChainClock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** Building a chain, planning its stages, and the contracts around a call. */
class ResilientChainTest {

    private static final Prompt PROMPT = Prompt.of("hello");

    private final ManualChainClock clock = new ManualChainClock();
    private final Recorder recorder = new Recorder();

    private ResilientChain.Builder<Prompt> builder(FakeProvider... providers) {
        ResilientChain.Builder<Prompt> builder = ResilientChain.<Prompt>builder()
                .clock(clock)
                .retry(RetryPolicy.none())
                .usageListener(recorder)
                .eventListener(recorder);
        for (FakeProvider provider : providers) {
            builder.provider(provider);
        }
        return builder;
    }

    @Test
    void theDefaultChain_isTheProvidersInRegistrationOrder() {
        ResilientChain<Prompt> chain = builder(new FakeProvider("Zeta"), new FakeProvider("alpha")).build();

        assertThat(chain.providerNames()).containsExactly("zeta", "alpha");
        assertThat(chain.defaultSpec()).hasToString("zeta,alpha");
        assertThat(chain.execute(PROMPT).stage().provider()).isEqualTo("zeta");
    }

    @Test
    void providerNames_mustBeUniqueAndUsableInASpec() {
        assertThatThrownBy(() -> builder(new FakeProvider("dup"), new FakeProvider("DUP")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder(new FakeProvider("a,b")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder(new FakeProvider("a:b")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ResilientChain.<Prompt>builder().build())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anUnknownProviderInTheSpec_isSkippedAndReported() {
        ResilientChain<Prompt> chain = builder(new FakeProvider("known")).build();

        ChainResult result = chain.execute(ChainSpec.parse("removed:model-x,known"), PROMPT, CallOptions.background());

        assertThat(result.stage().provider()).isEqualTo("known");
        assertThat(recorder.skips).containsExactly(
                new Recorder.Skip(new StageRef("removed", "model-x"), SkipReason.UNKNOWN_PROVIDER));
    }

    @Test
    void anUnavailableProvider_isSkipped_withoutCountingAsAFailure() {
        FakeProvider keyless = new FakeProvider("keyless");
        keyless.available = false;
        ResilientChain<Prompt> chain = builder(keyless, new FakeProvider("backup")).build();

        assertThat(chain.execute(PROMPT).stage().provider()).isEqualTo("backup");
        assertThat(keyless.calls()).isZero();
        assertThat(recorder.skips).extracting(Recorder.Skip::reason).containsExactly(SkipReason.UNAVAILABLE);
        assertThat(chain.breaker().snapshot()).isEmpty();
    }

    @Test
    void aSpecWithNothingRunnable_isAConfigurationError() {
        FakeProvider keyless = new FakeProvider("keyless");
        keyless.available = false;
        ResilientChain<Prompt> chain = builder(keyless).build();

        assertThatThrownBy(() -> chain.execute(PROMPT)).isInstanceOf(NoAvailableStageException.class);
        assertThatThrownBy(() -> chain.execute(ChainSpec.parse("ghost"), PROMPT, CallOptions.background()))
                .isInstanceOf(NoAvailableStageException.class);
    }

    @Test
    void anUnpinnedProvider_triesItsDefaultModelThenItsOtherModels_beforeTheNextProvider() {
        FakeProvider multi = new FakeProvider("multi", "m1", List.of("m2", "m1", " ", "m3"))
                .thenFail(new ProviderHttpException("multi", 503, null, null))
                .thenAnswer("from m2");
        ResilientChain<Prompt> chain = builder(multi, new FakeProvider("next")).build();

        ChainResult result = chain.execute(PROMPT);

        assertThat(result.text()).isEqualTo("from m2");
        assertThat(result.stage()).isEqualTo(new StageRef("multi", "m2"));
        assertThat(multi.calledModels()).containsExactly("m1", "m2");
    }

    @Test
    void aPinnedModel_isTheOnlyOneTried() {
        FakeProvider multi = new FakeProvider("multi", "m1", List.of("m2"))
                .thenFail(new ProviderHttpException("multi", 503, null, null));
        ResilientChain<Prompt> chain = builder(multi, new FakeProvider("next")).build();

        ChainResult result = chain.execute(ChainSpec.parse("multi:m9,next"), PROMPT, CallOptions.background());

        assertThat(multi.calledModels()).containsExactly("m9");
        assertThat(result.stage().provider()).isEqualTo("next");
    }

    @Test
    void aBlankAnswer_isAFailure_andTheChainMovesOn() {
        FakeProvider blank = new FakeProvider("blank").thenAnswer("   ");
        ResilientChain<Prompt> chain = builder(blank, new FakeProvider("next")).build();

        assertThat(chain.execute(PROMPT).stage().provider()).isEqualTo("next");
        assertThat(recorder.usage.getFirst().failure()).isInstanceOf(EmptyOutputException.class);
    }

    @Test
    void theResult_reportsTheModelThatAnswered_andTheAttempts() {
        FakeProvider aliasing = new FakeProvider("p", "alias", List.of())
                .then(call -> new LlmResult("text", "alias-2026", Usage.of(7, 3)));
        FakeProvider failing = new FakeProvider("f").thenFail(new ProviderHttpException("f", 401, null, null));
        ResilientChain<Prompt> chain = builder(failing, aliasing).build();

        ChainResult result = chain.execute(PROMPT);

        assertThat(result.model()).isEqualTo("alias-2026");
        assertThat(result.usage()).isEqualTo(Usage.of(7, 3));
        assertThat(result.attempts()).extracting(AttemptRecord::outcome).containsExactly(Outcome.FAILED, Outcome.WON);
        assertThat(recorder.usage.get(1).stage()).as("priced and reported on the model that answered")
                .isEqualTo(new StageRef("p", "alias-2026"));
    }

    @Test
    void labels_reachTheProviderAndTheUsageEvents() {
        List<Map<String, String>> seen = new CopyOnWriteArrayList<>();
        FakeProvider provider = new FakeProvider("p").then(call -> {
            seen.add(call.context.labels());
            return LlmResult.of("ok");
        });
        ResilientChain<Prompt> chain = builder(provider).build();

        chain.execute(PROMPT, CallOptions.background().withLabel("tenant", "t-1").withLabel("purpose", "summary"));

        assertThat(seen.getFirst()).containsEntry("tenant", "t-1").containsEntry("purpose", "summary");
        assertThat(recorder.usage.getFirst().labels()).containsEntry("tenant", "t-1");
    }

    @Test
    void throwingListeners_neverBreakTheCall_andAreCounted() {
        ResilientChain<Prompt> chain = ResilientChain.<Prompt>builder()
                .clock(clock)
                .provider(new FakeProvider("p"))
                .usageListener(event -> {
                    throw new IllegalStateException("database down");
                })
                .eventListener(new ChainEventListener() {
                    @Override
                    public void onStageStarted(StageRef stage, boolean hedge) {
                        throw new IllegalStateException("metrics down");
                    }
                })
                .build();

        assertThat(chain.execute(PROMPT).text()).isEqualTo("p answer");
        assertThat(chain.listenerFailures()).isEqualTo(2);
    }

    @Test
    void theContextPropagator_carriesTheCallersThreadLocals_toTheProvider() {
        ThreadLocal<String> tenant = new ThreadLocal<>();
        ContextPropagator propagator = task -> {
            String captured = tenant.get();
            return () -> {
                tenant.set(captured);
                try {
                    task.run();
                } finally {
                    tenant.remove();
                }
            };
        };
        List<String> seen = new CopyOnWriteArrayList<>();
        FakeProvider provider = new FakeProvider("p").otherwise(call -> {
            seen.add(tenant.get());
            return LlmResult.of("ok");
        });
        ResilientChain<Prompt> plain = builder(provider).contextPropagator(propagator).build();
        ResilientChain<Prompt> withTimeout = builder(provider).contextPropagator(propagator)
                .callTimeout(Duration.ofSeconds(30)).build();

        tenant.set("tenant-7");
        try {
            plain.execute(PROMPT);
            withTimeout.execute(PROMPT);
        } finally {
            tenant.remove();
        }

        assertThat(seen).containsExactly("tenant-7", "tenant-7");
    }
}

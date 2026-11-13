// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.peppe_git.resilientllmchain.breaker.BreakerConfig;
import io.github.peppe_git.resilientllmchain.breaker.BreakerSnapshot;
import io.github.peppe_git.resilientllmchain.breaker.BreakerStatus;
import io.github.peppe_git.resilientllmchain.error.ProviderHttpException;
import io.github.peppe_git.resilientllmchain.event.SkipReason;
import io.github.peppe_git.resilientllmchain.retry.RetryPolicy;
import io.github.peppe_git.resilientllmchain.time.ManualChainClock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class ChainBreakerTest {

    private static final Prompt PROMPT = Prompt.of("hello");
    private static final Duration COOLDOWN = Duration.ofSeconds(90);

    private final ManualChainClock clock = new ManualChainClock();
    private final Recorder recorder = new Recorder();

    private ResilientChain<Prompt> chain(BreakerConfig breaker, FakeProvider... providers) {
        ResilientChain.Builder<Prompt> builder = ResilientChain.<Prompt>builder()
                .clock(clock)
                .breaker(breaker)
                .retry(RetryPolicy.none())
                .usageListener(recorder)
                .eventListener(recorder);
        for (FakeProvider provider : providers) {
            builder.provider(provider);
        }
        return builder.build();
    }

    private static BreakerConfig threshold(int failures) {
        return BreakerConfig.builder()
                .failureThreshold(failures)
                .cooldown(COOLDOWN)
                .permanentCooldown(Duration.ofHours(6))
                .build();
    }

    private static ProviderHttpException http(int status) {
        return new ProviderHttpException("primary", status, null, null);
    }

    @Test
    void persistentFailure_skipsThePrimary_afterTheThreshold() {
        FakeProvider primary = new FakeProvider("primary").otherwise(FakeProvider.failing(http(503)));
        FakeProvider backup = new FakeProvider("backup");
        ResilientChain<Prompt> chain = chain(threshold(2), primary, backup);

        chain.execute(PROMPT);
        chain.execute(PROMPT);
        assertThat(primary.calls()).isEqualTo(2);

        ChainResult third = chain.execute(PROMPT);

        assertThat(third.stage().provider()).isEqualTo("backup");
        assertThat(primary.calls()).as("skipped while cooling down").isEqualTo(2);
        assertThat(recorder.skips).extracting(Recorder.Skip::reason).containsExactly(SkipReason.BREAKER_OPEN);
    }

    @Test
    void notFound_isPermanent_andSkipsThePrimaryAtOnce() {
        FakeProvider primary = new FakeProvider("primary").otherwise(FakeProvider.failing(http(404)));
        FakeProvider backup = new FakeProvider("backup");
        ResilientChain<Prompt> chain = chain(threshold(3), primary, backup);

        chain.execute(PROMPT);
        clock.advance(COOLDOWN.multipliedBy(5));
        chain.execute(PROMPT);

        assertThat(primary.calls()).isEqualTo(1);
        assertThat(recorder.permanent).extracting(StageRef::provider).containsExactly("primary");
    }

    @Test
    void gone_isPermanent_too() {
        FakeProvider primary = new FakeProvider("primary").otherwise(FakeProvider.failing(http(410)));
        FakeProvider backup = new FakeProvider("backup");
        ResilientChain<Prompt> chain = chain(threshold(3), primary, backup);

        chain.execute(PROMPT);
        chain.execute(PROMPT);

        assertThat(primary.calls()).isEqualTo(1);
    }

    @Test
    void theLastError_keepsTheBodyExcerpt_notJustTheStatus() {
        FakeProvider primary = new FakeProvider("primary").thenFail(
                new ProviderHttpException("primary", 410, null, "{\"detail\": \"this model was retired\"}"));
        ResilientChain<Prompt> chain = chain(threshold(3), primary, new FakeProvider("backup"));

        chain.execute(PROMPT);

        BreakerSnapshot snapshot = chain.breaker().snapshot().getFirst();
        assertThat(snapshot.lastError()).contains("410").contains("this model was retired");
    }

    @Test
    void halfOpen_triesTheStageAgainWhenTheCooldownExpires() {
        FakeProvider primary = new FakeProvider("primary").thenFail(http(503)).thenFail(http(503));
        FakeProvider backup = new FakeProvider("backup");
        ResilientChain<Prompt> chain = chain(threshold(2), primary, backup);
        chain.execute(PROMPT);
        chain.execute(PROMPT);
        assertThat(chain.execute(PROMPT).stage().provider()).isEqualTo("backup");

        clock.advance(COOLDOWN);
        ChainResult recovered = chain.execute(PROMPT);

        assertThat(recovered.stage().provider()).isEqualTo("primary");
        assertThat(chain.breaker().status(recovered.stage())).isEqualTo(BreakerStatus.CLOSED);
    }

    @Test
    void everyStageCoolingDown_theChainIsStillAttempted_failOpen() {
        FakeProvider only = new FakeProvider("only").thenFail(http(503)).thenFail(http(503));
        ResilientChain<Prompt> chain = chain(threshold(2), only);
        assertThatThrownBy(() -> chain.execute(PROMPT)).isInstanceOf(ChainExhaustedException.class);
        assertThatThrownBy(() -> chain.execute(PROMPT)).isInstanceOf(ChainExhaustedException.class);

        ChainResult result = chain.execute(PROMPT);

        assertThat(result.failOpen()).isTrue();
        assertThat(result.text()).isEqualTo("only answer");
        assertThat(recorder.failOpens).isEqualTo(1);
    }

    @Test
    void failOpen_triesTheWholeChain() {
        FakeProvider first = new FakeProvider("first").thenFail(http(404)).thenFail(http(503));
        FakeProvider second = new FakeProvider("second").thenFail(http(404));
        ResilientChain<Prompt> chain = chain(threshold(1), first, second);
        assertThatThrownBy(() -> chain.execute(PROMPT)).isInstanceOf(ChainExhaustedException.class);

        ChainResult result = chain.execute(PROMPT);

        assertThat(result.stage().provider()).isEqualTo("second");
        assertThat(result.failOpen()).isTrue();
        assertThat(first.calls()).isEqualTo(2);
    }

    @Test
    void aHealthyStageFailing_whileTheOthersCoolDown_propagatesTheRealError() {
        FakeProvider primary = new FakeProvider("primary").thenAnswer("ok").thenFail(http(401));
        FakeProvider backup = new FakeProvider("backup").thenFail(new ProviderHttpException("backup", 404, null, null));
        ResilientChain<Prompt> chain = chain(threshold(3), backup, primary);
        assertThat(chain.execute(PROMPT).stage().provider()).isEqualTo("primary");

        assertThatThrownBy(() -> chain.execute(PROMPT))
                .isInstanceOf(ChainExhaustedException.class)
                .cause().isInstanceOf(ProviderHttpException.class).hasMessageContaining("401");
        assertThat(recorder.failOpens).as("a real failure is not masked by a fail-open retry").isZero();
    }

    @Test
    void contentRefusals_doNotTouchTheBreaker() {
        FakeProvider primary = new FakeProvider("primary").otherwise(FakeProvider.failing(
                new io.github.peppe_git.resilientllmchain.error.ContentRefusedException("declined")));
        ResilientChain<Prompt> chain = chain(threshold(1), primary, new FakeProvider("backup"));

        chain.execute(PROMPT);
        chain.execute(PROMPT);

        assertThat(primary.calls()).isEqualTo(2);
        assertThat(chain.breaker().snapshot()).isEmpty();
    }

    @Test
    void stalledCalls_makeTheNextCallSkipTheStage_beforeAnyFailureCompletes() throws Exception {
        FakeProvider primary = new FakeProvider("primary").thenBlock().thenBlock();
        FakeProvider backup = new FakeProvider("backup");
        ResilientChain<Prompt> chain = chain(BreakerConfig.builder()
                .stallAfter(Duration.ofSeconds(30))
                .stallThreshold(2)
                .build(), primary, backup);
        CompletableFuture<ChainResult> first = Async.run(() -> chain.execute(PROMPT));
        CompletableFuture<ChainResult> second = Async.run(() -> chain.execute(PROMPT));
        FakeProvider.Call stuck1 = primary.awaitCall();
        FakeProvider.Call stuck2 = primary.awaitCall();

        clock.advance(Duration.ofSeconds(30));
        ChainResult third = chain.execute(PROMPT);

        assertThat(third.stage().provider()).isEqualTo("backup");
        assertThat(primary.calls()).isEqualTo(2);
        assertThat(chain.breaker().status(new StageRef("primary", "primary-model"))).isEqualTo(BreakerStatus.STALLED);

        stuck1.answer("late 1");
        stuck2.answer("late 2");
        // Which call entered the provider first is up to the scheduler: only the pair is certain.
        assertThat(List.of(Async.join(first).text(), Async.join(second).text()))
                .containsExactlyInAnyOrder("late 1", "late 2");
        assertThat(chain.breaker().status(new StageRef("primary", "primary-model"))).isEqualTo(BreakerStatus.CLOSED);
    }
}

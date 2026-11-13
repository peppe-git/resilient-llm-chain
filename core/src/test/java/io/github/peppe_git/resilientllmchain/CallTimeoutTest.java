// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.peppe_git.resilientllmchain.cost.AbandonedSpendEstimator;
import io.github.peppe_git.resilientllmchain.cost.ModelPrice;
import io.github.peppe_git.resilientllmchain.cost.PriceTable;
import io.github.peppe_git.resilientllmchain.error.CallTimeoutException;
import io.github.peppe_git.resilientllmchain.error.ProviderHttpException;
import io.github.peppe_git.resilientllmchain.event.UsageEvent;
import io.github.peppe_git.resilientllmchain.retry.RetryPolicy;
import io.github.peppe_git.resilientllmchain.time.ManualChainClock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The total time cap per provider call, beyond any socket read timeout. */
class CallTimeoutTest {

    private static final Prompt PROMPT = new Prompt("system text", "user text that is forty characters long.");
    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private static final Duration SAFETY = Duration.ofSeconds(5);

    private final ManualChainClock clock = new ManualChainClock();
    private final Recorder recorder = new Recorder();

    private ResilientChain.Builder<Prompt> chain(FakeProvider... providers) {
        ResilientChain.Builder<Prompt> builder = ResilientChain.<Prompt>builder()
                .clock(clock)
                .retry(RetryPolicy.none())
                .callTimeout(TIMEOUT)
                .usageListener(recorder)
                .eventListener(recorder);
        for (FakeProvider provider : providers) {
            builder.provider(provider);
        }
        return builder;
    }

    @Test
    void aCallWithinTheCap_returnsItsResult_andLeavesNoTimerBehind() {
        FakeProvider provider = new FakeProvider("p");
        ResilientChain<Prompt> chain = chain(provider).build();

        assertThat(chain.execute(PROMPT).text()).isEqualTo("p answer");
        assertThat(clock.pendingTimers()).isZero();
    }

    @Test
    void aCallBeyondTheCap_isAbandoned_andTheNextStageServes() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        FakeProvider primary = new FakeProvider("primary").then(call -> {
            try {
                return call.reply.get();
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
        });
        FakeProvider backup = new FakeProvider("backup");
        ResilientChain<Prompt> chain = chain(primary, backup).build();

        CompletableFuture<ChainResult> call = Async.run(() -> chain.execute(PROMPT));
        primary.awaitCall();
        assertThat(clock.awaitTimers(1, SAFETY)).isTrue();
        clock.advance(TIMEOUT.minusMillis(1));
        assertThat(call).isNotDone();
        clock.advance(Duration.ofMillis(1));

        ChainResult result = Async.join(call);
        assertThat(result.stage().provider()).isEqualTo("backup");
        UsageEvent abandoned = recorder.usage.getFirst();
        assertThat(abandoned.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(abandoned.failure()).isInstanceOf(CallTimeoutException.class);
        assertThat(abandoned.latency()).isEqualTo(TIMEOUT);
        assertThat(interrupted.await(5, TimeUnit.SECONDS)).as("the abandoned call thread is interrupted").isTrue();
    }

    @Test
    void providerExceptions_passThroughUnchanged() {
        FakeProvider primary = new FakeProvider("primary").thenFail(new ProviderHttpException("primary", 404, null, null));
        ResilientChain<Prompt> chain = chain(primary, new FakeProvider("backup")).build();

        chain.execute(PROMPT);

        assertThat(recorder.permanent).as("classified from the original exception")
                .extracting(StageRef::provider).containsExactly("primary");
    }

    @Test
    void anAbandonedCall_reportsAnEstimatedSpend() throws Exception {
        FakeProvider primary = new FakeProvider("primary").thenBlock();
        ResilientChain<Prompt> chain = chain(primary, new FakeProvider("backup"))
                .abandonedSpendEstimator(AbandonedSpendEstimator.characterHeuristic(Prompt::characters, 1_000))
                .priceTable(PriceTable.builder().providerDefault("primary", ModelPrice.perMillion(1_000, 2_000)).build())
                .build();

        CompletableFuture<ChainResult> call = Async.run(() -> chain.execute(PROMPT));
        FakeProvider.Call stuck = primary.awaitCall();
        assertThat(clock.awaitTimers(1, SAFETY)).isTrue();
        clock.advance(TIMEOUT);
        Async.join(call);

        UsageEvent abandoned = recorder.usage.getFirst();
        assertThat(abandoned.estimated()).isTrue();
        assertThat(abandoned.usage()).isEqualTo(Usage.of(PROMPT.characters() / 4, 1_000));
        assertThat(abandoned.cost().getAsDouble())
                .isEqualTo((PROMPT.characters() / 4 * 1_000d + 1_000 * 2_000d) / 1_000_000d);
        stuck.answer("too late");
    }

    @Test
    void anExplicitRejection_isNeverEstimated() {
        FakeProvider primary = new FakeProvider("primary").thenFail(new ProviderHttpException("primary", 429, null, null));
        ResilientChain<Prompt> chain = chain(primary, new FakeProvider("backup"))
                .abandonedSpendEstimator(AbandonedSpendEstimator.characterHeuristic(Prompt::characters, 1_000))
                .build();

        chain.execute(PROMPT);

        UsageEvent rejected = recorder.usage.getFirst();
        assertThat(rejected.estimated()).isFalse();
        assertThat(rejected.usage()).isEqualTo(Usage.ZERO);
    }

    @Test
    void aPerProviderCap_overridesTheDefaultOne() throws Exception {
        FakeProvider primary = new FakeProvider("primary").thenBlock();
        ResilientChain<Prompt> chain = chain(primary, new FakeProvider("backup"))
                .callTimeout("primary", Duration.ofSeconds(5))
                .build();

        CompletableFuture<ChainResult> call = Async.run(() -> chain.execute(PROMPT));
        FakeProvider.Call stuck = primary.awaitCall();
        assertThat(clock.awaitTimers(1, SAFETY)).isTrue();
        clock.advance(Duration.ofSeconds(5));

        assertThat(Async.join(call).stage().provider()).isEqualTo("backup");
        stuck.answer("late");
    }
}

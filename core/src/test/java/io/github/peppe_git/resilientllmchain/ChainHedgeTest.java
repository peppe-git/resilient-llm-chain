// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.peppe_git.resilientllmchain.cost.ModelPrice;
import io.github.peppe_git.resilientllmchain.cost.PriceTable;
import io.github.peppe_git.resilientllmchain.error.ProviderHttpException;
import io.github.peppe_git.resilientllmchain.event.HedgeSuppression;
import io.github.peppe_git.resilientllmchain.event.UsageEvent;
import io.github.peppe_git.resilientllmchain.hedge.HedgePolicy;
import io.github.peppe_git.resilientllmchain.limit.ConcurrencyLimiter;
import io.github.peppe_git.resilientllmchain.retry.RetryPolicy;
import io.github.peppe_git.resilientllmchain.time.ManualChainClock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Hedging, driven by a manual clock: no test sleeps. A blocked fake provider stays silent until the test
 * answers for it; the hedge fires only when the test advances the clock past the delay.
 */
class ChainHedgeTest {

    private static final Prompt PROMPT = Prompt.of("hello");
    private static final Duration DELAY = Duration.ofSeconds(10);
    private static final Duration SAFETY = Duration.ofSeconds(5);

    private final ManualChainClock clock = new ManualChainClock();
    private final Recorder recorder = new Recorder();

    private ResilientChain.Builder<Prompt> chain(FakeProvider... providers) {
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

    private static PriceTable prices(Object... providerAndPrice) {
        PriceTable.Builder table = PriceTable.builder();
        for (int i = 0; i < providerAndPrice.length; i += 2) {
            double price = ((Number) providerAndPrice[i + 1]).doubleValue();
            table.providerDefault((String) providerAndPrice[i], ModelPrice.perMillion(price, price));
        }
        return table.build();
    }

    @Test
    void silentPrimary_isOvertakenByTheNextStage_withoutWaitingForIt() throws Exception {
        FakeProvider primary = new FakeProvider("primary").thenBlock();
        FakeProvider backup = new FakeProvider("backup");
        ResilientChain<Prompt> chain = chain(primary, backup).hedging(HedgePolicy.after(DELAY)).build();

        CompletableFuture<ChainResult> call = Async.run(() -> chain.execute(PROMPT));
        FakeProvider.Call stuck = primary.awaitCall();
        assertThat(clock.awaitTimers(1, SAFETY)).isTrue();

        clock.advance(DELAY.minusMillis(1));
        assertThat(backup.calls()).isZero();
        assertThat(call).isNotDone();

        clock.advance(Duration.ofMillis(1));
        ChainResult result = Async.join(call);

        assertThat(result.stage().provider()).isEqualTo("backup");
        assertThat(result.text()).isEqualTo("backup answer");
        assertThat(recorder.hedges).extracting(StageRef::provider).containsExactly("backup");
        stuck.answer("late");
    }

    @Test
    void overtakenStage_reportsItsSpendAsDiscarded_notAsAWin() throws Exception {
        FakeProvider primary = new FakeProvider("primary").thenBlock();
        FakeProvider backup = new FakeProvider("backup");
        ResilientChain<Prompt> chain = chain(primary, backup).hedging(HedgePolicy.after(DELAY)).build();

        CompletableFuture<ChainResult> call = Async.run(() -> chain.execute(PROMPT));
        FakeProvider.Call stuck = primary.awaitCall();
        assertThat(clock.awaitTimers(1, SAFETY)).isTrue();
        clock.advance(DELAY);
        assertThat(Async.join(call).stage().provider()).isEqualTo("backup");

        stuck.answer("late primary");
        UsageEvent discarded = recorder.awaitUsage(Outcome.DISCARDED);

        assertThat(discarded.stage().provider()).isEqualTo("primary");
        assertThat(discarded.usage()).isEqualTo(Usage.of(100, 50));
        assertThat(recorder.usage).filteredOn(e -> e.outcome() == Outcome.WON)
                .extracting(e -> e.stage().provider()).containsExactly("backup");
    }

    @Test
    void fastPrimary_wins_andTheNextStageIsNeverCalled() {
        FakeProvider primary = new FakeProvider("primary");
        FakeProvider backup = new FakeProvider("backup");
        ResilientChain<Prompt> chain = chain(primary, backup).hedging(HedgePolicy.after(DELAY)).build();

        ChainResult result = chain.execute(PROMPT);

        assertThat(result.stage().provider()).isEqualTo("primary");
        assertThat(backup.calls()).isZero();
        assertThat(clock.pendingTimers()).as("the hedge timer is cancelled with the call").isZero();
    }

    @Test
    void immediateFailure_fallsBackAtOnce_withoutWaitingForTheHedgeDelay() {
        FakeProvider primary = new FakeProvider("primary").thenFail(new ProviderHttpException("primary", 401, null, null));
        FakeProvider backup = new FakeProvider("backup");
        ResilientChain<Prompt> chain = chain(primary, backup).hedging(HedgePolicy.after(DELAY)).build();

        ChainResult result = chain.execute(PROMPT);

        assertThat(result.stage().provider()).isEqualTo("backup");
        assertThat(recorder.hedges).as("a fallback after a failure is not a hedge").isEmpty();
    }

    @Test
    void disabledPolicy_keepsTheChainStrictlySerial() throws Exception {
        FakeProvider primary = new FakeProvider("primary").thenBlock();
        FakeProvider backup = new FakeProvider("backup");
        ResilientChain<Prompt> chain = chain(primary, backup).build();

        CompletableFuture<ChainResult> call = Async.run(() -> chain.execute(PROMPT));
        FakeProvider.Call stuck = primary.awaitCall();
        clock.advance(Duration.ofHours(1));

        assertThat(clock.pendingTimers()).isZero();
        assertThat(backup.calls()).isZero();

        stuck.fail(new ProviderHttpException("primary", 401, null, null));
        assertThat(Async.join(call).stage().provider()).isEqualTo("backup");
    }

    @Test
    void timerReArms_soAThirdStageIsHedgedAfterAnotherDelay() throws Exception {
        FakeProvider first = new FakeProvider("first").thenBlock();
        FakeProvider second = new FakeProvider("second").thenBlock();
        FakeProvider third = new FakeProvider("third");
        ResilientChain<Prompt> chain = chain(first, second, third).hedging(HedgePolicy.after(DELAY)).build();

        CompletableFuture<ChainResult> call = Async.run(() -> chain.execute(PROMPT));
        FakeProvider.Call firstCall = first.awaitCall();
        assertThat(clock.awaitTimers(1, SAFETY)).isTrue();
        clock.advance(DELAY);
        FakeProvider.Call secondCall = second.awaitCall();
        assertThat(clock.awaitTimers(1, SAFETY)).isTrue();
        assertThat(third.calls()).isZero();

        clock.advance(DELAY);

        assertThat(Async.join(call).stage().provider()).isEqualTo("third");
        assertThat(recorder.hedges).extracting(StageRef::provider).containsExactly("second", "third");
        firstCall.answer("late");
        secondCall.answer("late");
    }

    @Test
    void expensiveNextStage_isNotHedged_theCallWaitsInLine() throws Exception {
        FakeProvider primary = new FakeProvider("primary").thenBlock();
        FakeProvider backup = new FakeProvider("backup");
        ResilientChain<Prompt> chain = chain(primary, backup)
                .hedging(HedgePolicy.after(DELAY))
                .priceTable(prices("primary", 1.0, "backup", 10.0))
                .build();

        CompletableFuture<ChainResult> call = Async.run(() -> chain.execute(PROMPT));
        FakeProvider.Call stuck = primary.awaitCall();
        assertThat(clock.awaitTimers(1, SAFETY)).isTrue();
        clock.advance(DELAY);

        assertThat(recorder.awaitSuppressed().reason()).isEqualTo(HedgeSuppression.COST_CAP);
        assertThat(backup.calls()).isZero();

        stuck.answer("primary answer");
        assertThat(Async.join(call).stage().provider()).isEqualTo("primary");
        assertThat(backup.calls()).isZero();
    }

    @Test
    void expensiveStage_stillRunsAsAPlainFallback_whenNothingElseIsInFlight() throws Exception {
        FakeProvider primary = new FakeProvider("primary").thenBlock();
        FakeProvider backup = new FakeProvider("backup");
        ResilientChain<Prompt> chain = chain(primary, backup)
                .hedging(HedgePolicy.after(DELAY))
                .priceTable(prices("primary", 1.0, "backup", 10.0))
                .build();

        CompletableFuture<ChainResult> call = Async.run(() -> chain.execute(PROMPT));
        FakeProvider.Call stuck = primary.awaitCall();
        assertThat(clock.awaitTimers(1, SAFETY)).isTrue();
        clock.advance(DELAY);
        recorder.awaitSuppressed();

        stuck.fail(new ProviderHttpException("primary", 500, null, null));

        assertThat(Async.join(call).stage().provider()).isEqualTo("backup");
    }

    @Test
    void expensiveThirdStage_doesNotStartWhileThePrimaryIsStillRunning() throws Exception {
        // The hedged second stage fails at once while the primary still runs. The third would then start
        // in parallel with the primary: the cost rule applies to it too, against every stage in flight.
        FakeProvider primary = new FakeProvider("primary").thenBlock();
        FakeProvider second = new FakeProvider("second").thenFail(new ProviderHttpException("second", 503, null, null));
        FakeProvider third = new FakeProvider("third");
        ResilientChain<Prompt> chain = chain(primary, second, third)
                .hedging(HedgePolicy.after(DELAY))
                .priceTable(prices("primary", 1.0, "second", 1.5, "third", 10.0))
                .build();

        CompletableFuture<ChainResult> call = Async.run(() -> chain.execute(PROMPT));
        FakeProvider.Call stuck = primary.awaitCall();
        assertThat(clock.awaitTimers(1, SAFETY)).isTrue();
        clock.advance(DELAY);

        Recorder.Suppressed suppressed = recorder.awaitSuppressed();
        assertThat(suppressed.candidate().provider()).isEqualTo("third");
        assertThat(suppressed.reason()).isEqualTo(HedgeSuppression.COST_CAP);
        assertThat(second.calls()).isEqualTo(1);
        assertThat(third.calls()).isZero();

        stuck.answer("primary answer");
        assertThat(Async.join(call).stage().provider()).isEqualTo("primary");
        assertThat(third.calls()).isZero();
    }

    @Test
    void unpinnedStages_arePricedWithTheirDefaultModel() throws Exception {
        // Prices exist only for the exact models. An unpinned stage runs on its provider's default model,
        // and that is the price that counts: pricing a null model would let any hedge through.
        FakeProvider primary = new FakeProvider("primary", "small", List.of()).thenBlock();
        FakeProvider backup = new FakeProvider("backup", "large", List.of());
        PriceTable table = PriceTable.builder()
                .price("primary", "small", ModelPrice.perMillion(1, 1))
                .price("backup", "large", ModelPrice.perMillion(6, 6))
                .build();
        ResilientChain<Prompt> chain = chain(primary, backup)
                .hedging(HedgePolicy.after(DELAY)).priceTable(table).build();

        CompletableFuture<ChainResult> call = Async.run(() -> chain.execute(ChainSpec.parse("primary,backup"), PROMPT,
                CallOptions.background()));
        FakeProvider.Call stuck = primary.awaitCall();
        assertThat(clock.awaitTimers(1, SAFETY)).isTrue();
        clock.advance(DELAY);

        assertThat(recorder.awaitSuppressed().candidate()).isEqualTo(new StageRef("backup", "large"));
        stuck.answer("primary answer");
        assertThat(Async.join(call).stage()).isEqualTo(new StageRef("primary", "small"));
    }

    @Test
    void slightlyDearerNextStage_isStillHedged_andWins() throws Exception {
        FakeProvider primary = new FakeProvider("primary").thenBlock();
        FakeProvider backup = new FakeProvider("backup");
        ResilientChain<Prompt> chain = chain(primary, backup)
                .hedging(HedgePolicy.after(DELAY))
                .priceTable(prices("primary", 1.0, "backup", 1.5))
                .build();

        CompletableFuture<ChainResult> call = Async.run(() -> chain.execute(PROMPT));
        FakeProvider.Call stuck = primary.awaitCall();
        assertThat(clock.awaitTimers(1, SAFETY)).isTrue();
        clock.advance(DELAY);

        assertThat(Async.join(call).stage().provider()).isEqualTo("backup");
        assertThat(recorder.suppressed).isEmpty();
        stuck.answer("late");
    }

    @Test
    void unknownPrices_neverBlockAHedge() throws Exception {
        FakeProvider primary = new FakeProvider("primary").thenBlock();
        FakeProvider backup = new FakeProvider("backup");
        ResilientChain<Prompt> chain = chain(primary, backup)
                .hedging(HedgePolicy.after(DELAY))
                .priceTable(prices("backup", 50.0))
                .build();

        CompletableFuture<ChainResult> call = Async.run(() -> chain.execute(PROMPT));
        FakeProvider.Call stuck = primary.awaitCall();
        assertThat(clock.awaitTimers(1, SAFETY)).isTrue();
        clock.advance(DELAY);

        assertThat(Async.join(call).stage().provider()).isEqualTo("backup");
        stuck.answer("late");
    }

    @Test
    void saturatedLimiter_suppressesHedging() throws Exception {
        FakeProvider primary = new FakeProvider("primary").thenBlock();
        FakeProvider backup = new FakeProvider("backup");
        ResilientChain<Prompt> chain = chain(primary, backup)
                .hedging(HedgePolicy.after(DELAY))
                .concurrencyLimiter(ConcurrencyLimiter.of(1, Duration.ZERO))
                .build();

        CompletableFuture<ChainResult> call = Async.run(() -> chain.execute(PROMPT));
        FakeProvider.Call stuck = primary.awaitCall();
        assertThat(clock.awaitTimers(1, SAFETY)).isTrue();
        clock.advance(DELAY);

        assertThat(recorder.awaitSuppressed().reason()).isEqualTo(HedgeSuppression.LIMITER_SATURATED);
        stuck.answer("primary answer");
        assertThat(Async.join(call).stage().provider()).isEqualTo("primary");
        assertThat(backup.calls()).isZero();
    }

    @Test
    void interactiveCall_hedgesOnTheShortDelay() throws Exception {
        FakeProvider primary = new FakeProvider("primary").thenBlock();
        FakeProvider backup = new FakeProvider("backup");
        ResilientChain<Prompt> chain = chain(primary, backup)
                .hedging(HedgePolicy.builder()
                        .backgroundDelay(Duration.ofSeconds(20))
                        .interactiveDelay(Duration.ofSeconds(5))
                        .build())
                .build();

        CompletableFuture<ChainResult> call = Async.run(() -> chain.execute(PROMPT, CallOptions.interactive()));
        FakeProvider.Call stuck = primary.awaitCall();
        assertThat(clock.awaitTimers(1, SAFETY)).isTrue();
        clock.advance(Duration.ofSeconds(5));

        assertThat(Async.join(call).stage().provider()).isEqualTo("backup");
        stuck.answer("late");
    }

    @Test
    void backgroundCall_keepsTheLongDelay() throws Exception {
        FakeProvider primary = new FakeProvider("primary").thenBlock();
        FakeProvider backup = new FakeProvider("backup");
        ResilientChain<Prompt> chain = chain(primary, backup)
                .hedging(HedgePolicy.builder()
                        .backgroundDelay(Duration.ofSeconds(20))
                        .interactiveDelay(Duration.ofSeconds(5))
                        .build())
                .build();

        CompletableFuture<ChainResult> call = Async.run(() -> chain.execute(PROMPT, CallOptions.background()));
        FakeProvider.Call stuck = primary.awaitCall();
        assertThat(clock.awaitTimers(1, SAFETY)).isTrue();
        clock.advance(Duration.ofSeconds(5));
        assertThat(backup.calls()).isZero();
        assertThat(clock.pendingTimers()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(15));

        assertThat(Async.join(call).stage().provider()).isEqualTo("backup");
        stuck.answer("late");
    }

    @Test
    void streamingStage_thatAlreadyWrote_isNotHedged_evenPastTheDelay() throws Exception {
        CountDownLatch emitNow = new CountDownLatch(1);
        FakeProvider primary = new FakeProvider("primary").then(call -> {
            emitNow.await(5, TimeUnit.SECONDS);
            call.context.emit("Hel");
            return FakeProvider.blocking().run(call);
        });
        FakeProvider backup = new FakeProvider("backup");
        ResilientChain<Prompt> chain = chain(primary, backup).hedging(HedgePolicy.after(DELAY)).build();
        BlockingQueue<String> preview = new LinkedBlockingQueue<>();

        CompletableFuture<ChainResult> call = Async.run(() -> chain.execute(PROMPT,
                CallOptions.interactive().withStream(listener(preview))));
        FakeProvider.Call stuck = primary.awaitCall();
        assertThat(clock.awaitTimers(1, SAFETY)).isTrue();
        emitNow.countDown();
        assertThat(preview.poll(5, TimeUnit.SECONDS)).isEqualTo("Hel");

        clock.advance(DELAY);

        assertThat(recorder.awaitSuppressed().reason()).isEqualTo(HedgeSuppression.STREAM_STARTED);
        stuck.answer("Hello");
        assertThat(Async.join(call).text()).isEqualTo("Hello");
        assertThat(backup.calls()).isZero();
    }

    @Test
    void streamingCall_withASilentPrimary_isStillHedged() throws Exception {
        FakeProvider primary = new FakeProvider("primary").thenBlock();
        FakeProvider backup = new FakeProvider("backup").then(call -> {
            call.context.emit("B");
            return LlmResult.of("Backup", call.model, 1, 1);
        });
        ResilientChain<Prompt> chain = chain(primary, backup).hedging(HedgePolicy.after(DELAY)).build();
        BlockingQueue<String> preview = new LinkedBlockingQueue<>();

        CompletableFuture<ChainResult> call = Async.run(() -> chain.execute(PROMPT,
                CallOptions.interactive().withStream(listener(preview))));
        FakeProvider.Call stuck = primary.awaitCall();
        assertThat(clock.awaitTimers(1, SAFETY)).isTrue();
        clock.advance(DELAY);

        assertThat(Async.join(call).text()).isEqualTo("Backup");
        assertThat(preview).containsExactly("B");
        stuck.answer("late");
    }

    private static StreamListener listener(BlockingQueue<String> sink) {
        return new StreamListener() {
            @Override
            public void delta(String text) {
                sink.add(text);
            }

            @Override
            public void reset() {
                sink.add("<reset>");
            }
        };
    }
}

// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.peppe_git.resilientllmchain.breaker.BreakerConfig;
import io.github.peppe_git.resilientllmchain.error.ProviderHttpException;
import io.github.peppe_git.resilientllmchain.retry.RetryPolicy;
import io.github.peppe_git.resilientllmchain.time.ManualChainClock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** Retries on the same stage. The manual clock's sleep advances time, so waits are measured exactly. */
class ChainRetryTest {

    private static final Prompt PROMPT = Prompt.of("hello");

    private final ManualChainClock clock = new ManualChainClock();
    private final Recorder recorder = new Recorder();

    private ResilientChain<Prompt> chain(RetryPolicy retry, FakeProvider... providers) {
        ResilientChain.Builder<Prompt> builder = ResilientChain.<Prompt>builder()
                .clock(clock)
                .retry(retry)
                .breaker(BreakerConfig.builder().failureThreshold(1).build())
                .usageListener(recorder)
                .eventListener(recorder);
        for (FakeProvider provider : providers) {
            builder.provider(provider);
        }
        return builder.build();
    }

    /** Jitter factor exactly 1.0: the backoff is its deterministic value. */
    private static RetryPolicy.Builder noJitter() {
        return RetryPolicy.builder().random(() -> 0.5);
    }

    private static ProviderHttpException http(int status, String retryAfter) {
        return new ProviderHttpException("p", status, retryAfter, null);
    }

    @Test
    void transientErrors_areRetriedOnTheSameStage_withExponentialBackoff() {
        List<Integer> attempts = new CopyOnWriteArrayList<>();
        FakeProvider provider = new FakeProvider("p")
                .then(call -> {
                    attempts.add(call.context.attempt());
                    throw http(503, null);
                }, call -> {
                    attempts.add(call.context.attempt());
                    throw http(503, null);
                }, call -> {
                    attempts.add(call.context.attempt());
                    return LlmResult.of("third time lucky", call.model, 1, 1);
                });
        ResilientChain<Prompt> chain = chain(noJitter().baseDelay(Duration.ofMillis(500)).build(), provider);
        long start = clock.millis();

        ChainResult result = chain.execute(PROMPT);

        assertThat(result.text()).isEqualTo("third time lucky");
        assertThat(attempts).containsExactly(1, 2, 3);
        assertThat(clock.millis() - start).as("500 ms, then 1000 ms").isEqualTo(1_500);
        assertThat(chain.breaker().snapshot()).as("a success after retries is a success").isEmpty();
    }

    @Test
    void theProvidersRetryAfter_isHonouredExactly_withoutJitter() {
        FakeProvider provider = new FakeProvider("p").thenFail(http(429, "2"));
        RetryPolicy jittery = RetryPolicy.builder().random(() -> 0.0).build();
        ResilientChain<Prompt> chain = chain(jittery, provider);
        long start = clock.millis();

        chain.execute(PROMPT);

        assertThat(clock.millis() - start).isEqualTo(2_000);
    }

    @Test
    void theProvidersRetryAfter_isCapped() {
        FakeProvider provider = new FakeProvider("p").thenFail(http(503, "120"));
        ResilientChain<Prompt> chain = chain(noJitter().maxRetryAfter(Duration.ofSeconds(5)).build(), provider);
        long start = clock.millis();

        chain.execute(PROMPT);

        assertThat(clock.millis() - start).isEqualTo(5_000);
    }

    @Test
    void nonRetryableErrors_moveToTheNextStageAtOnce() {
        FakeProvider primary = new FakeProvider("primary").thenFail(http(400, null));
        FakeProvider backup = new FakeProvider("backup");
        ResilientChain<Prompt> chain = chain(RetryPolicy.defaults(), primary, backup);
        long start = clock.millis();

        assertThat(chain.execute(PROMPT).stage().provider()).isEqualTo("backup");
        assertThat(primary.calls()).isEqualTo(1);
        assertThat(clock.millis()).isEqualTo(start);
    }

    @Test
    void exhaustedRetries_countAsOneBreakerFailure_andTheChainMovesOn() {
        FakeProvider primary = new FakeProvider("primary").otherwise(FakeProvider.failing(http(503, null)));
        FakeProvider backup = new FakeProvider("backup");
        ResilientChain<Prompt> chain = chain(noJitter().maxAttempts(3).build(), primary, backup);

        assertThat(chain.execute(PROMPT).stage().provider()).isEqualTo("backup");

        assertThat(primary.calls()).isEqualTo(3);
        assertThat(chain.breaker().snapshot().getFirst().consecutiveFailures()).isEqualTo(1);
    }

    @Test
    void cancellation_stopsFurtherRetries_andTheFallback() {
        CancellationSource cancellation = new CancellationSource();
        FakeProvider primary = new FakeProvider("primary").then(call -> {
            cancellation.cancel();
            throw http(503, null);
        });
        FakeProvider backup = new FakeProvider("backup");
        ResilientChain<Prompt> chain = chain(RetryPolicy.defaults(), primary, backup);

        assertThatThrownBy(() -> chain.execute(PROMPT, CallOptions.background().withCancellation(cancellation)))
                .isInstanceOf(ChainCancelledException.class);
        assertThat(primary.calls()).isEqualTo(1);
        assertThat(backup.calls()).isZero();
    }
}

// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.internal;

import io.github.peppe_git.resilientllmchain.ChainSpec;
import io.github.peppe_git.resilientllmchain.ContextPropagator;
import io.github.peppe_git.resilientllmchain.LlmProvider;
import io.github.peppe_git.resilientllmchain.OutputValidator;
import io.github.peppe_git.resilientllmchain.breaker.StageBreaker;
import io.github.peppe_git.resilientllmchain.cost.AbandonedSpendEstimator;
import io.github.peppe_git.resilientllmchain.cost.PriceTable;
import io.github.peppe_git.resilientllmchain.error.ErrorClassifier;
import io.github.peppe_git.resilientllmchain.event.ChainEventListener;
import io.github.peppe_git.resilientllmchain.event.UsageListener;
import io.github.peppe_git.resilientllmchain.hedge.HedgePolicy;
import io.github.peppe_git.resilientllmchain.limit.ConcurrencyLimiter;
import io.github.peppe_git.resilientllmchain.retry.RetryPolicy;
import io.github.peppe_git.resilientllmchain.time.ChainClock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Immutable configuration of a chain, assembled by {@code ResilientChain.Builder}. Internal API: not
 * covered by compatibility guarantees.
 *
 * @param providers   registered providers by lower-case name, in registration order
 * @param callTimeout total time cap per provider call; {@link Duration#ZERO} = none
 */
public record ChainSettings<Q>(
        Map<String, LlmProvider<Q>> providers,
        ChainSpec defaultSpec,
        ChainClock clock,
        StageBreaker breaker,
        HedgePolicy hedge,
        PriceTable prices,
        RetryPolicy retry,
        Duration callTimeout,
        Map<String, Duration> callTimeoutByProvider,
        ErrorClassifier classifier,
        OutputValidator validator,
        int maxConsecutiveRejections,
        UsageListener usageListener,
        ChainEventListener eventListener,
        ConcurrencyLimiter limiter,
        AbandonedSpendEstimator<Q> spendEstimator,
        ContextPropagator propagator,
        Executor stageExecutor,
        AtomicLong listenerFailures) {

    Duration callTimeoutFor(String provider) {
        return callTimeoutByProvider.getOrDefault(provider, callTimeout);
    }
}

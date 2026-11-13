// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

import io.github.peppe_git.resilientllmchain.breaker.BreakerConfig;
import io.github.peppe_git.resilientllmchain.breaker.SharedVerdictStore;
import io.github.peppe_git.resilientllmchain.breaker.StageBreaker;
import io.github.peppe_git.resilientllmchain.cost.AbandonedSpendEstimator;
import io.github.peppe_git.resilientllmchain.cost.PriceTable;
import io.github.peppe_git.resilientllmchain.error.DefaultErrorClassifier;
import io.github.peppe_git.resilientllmchain.error.ErrorClassifier;
import io.github.peppe_git.resilientllmchain.event.ChainEventListener;
import io.github.peppe_git.resilientllmchain.event.UsageListener;
import io.github.peppe_git.resilientllmchain.hedge.HedgePolicy;
import io.github.peppe_git.resilientllmchain.internal.ChainRun;
import io.github.peppe_git.resilientllmchain.internal.ChainSettings;
import io.github.peppe_git.resilientllmchain.limit.ConcurrencyLimiter;
import io.github.peppe_git.resilientllmchain.retry.RetryPolicy;
import io.github.peppe_git.resilientllmchain.time.ChainClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An ordered fallback chain of LLM providers with hedging and a per-stage breaker. Immutable and
 * thread-safe: build it once and share it.
 *
 * <pre>{@code
 * ResilientChain<Prompt> chain = ResilientChain.<Prompt>builder()
 *         .provider(primary)
 *         .provider(backup)
 *         .hedging(HedgePolicy.builder()
 *                 .backgroundDelay(Duration.ofSeconds(20))
 *                 .interactiveDelay(Duration.ofSeconds(8))
 *                 .build())
 *         .priceTable(prices)
 *         .callTimeout(Duration.ofSeconds(90))
 *         .build();
 *
 * ChainResult answer = chain.execute(Prompt.of("Summarise ..."), CallOptions.interactive());
 * }</pre>
 *
 * @param <Q> the request type shared by the providers
 */
public final class ResilientChain<Q> {

    private static final ExecutorService DEFAULT_STAGE_EXECUTOR =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("resilient-llm-chain-stage-", 0).factory());

    private final ChainSettings<Q> settings;

    private ResilientChain(ChainSettings<Q> settings) {
        this.settings = settings;
    }

    public static <Q> Builder<Q> builder() {
        return new Builder<>();
    }

    /** Runs the default chain (all providers in registration order) as background work. */
    public ChainResult execute(Q request) {
        return execute(settings.defaultSpec(), request, CallOptions.background());
    }

    /** Runs the default chain with {@code options}. */
    public ChainResult execute(Q request, CallOptions options) {
        return execute(settings.defaultSpec(), request, options);
    }

    /**
     * Runs {@code spec}.
     *
     * @throws ChainExhaustedException    every attempted stage failed
     * @throws NoAvailableStageException  no stage of the spec is registered and available
     * @throws ChainCancelledException    cancelled, or the calling thread was interrupted
     * @throws ChainSaturatedException    interactive call without a concurrency permit in time
     */
    public ChainResult execute(ChainSpec spec, Q request, CallOptions options) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(options, "options");
        return settings.limiter().call(options.isInteractive(),
                () -> new ChainRun<>(settings, spec, request, options).run());
    }

    /** The breaker, for snapshots and manual resets. */
    public StageBreaker breaker() {
        return settings.breaker();
    }

    /** The concurrency limiter, for its wait metrics. */
    public ConcurrencyLimiter limiter() {
        return settings.limiter();
    }

    /** Names of the registered providers, in registration order. */
    public List<String> providerNames() {
        return List.copyOf(settings.providers().keySet());
    }

    /** The chain used by {@link #execute(Object)}. */
    public ChainSpec defaultSpec() {
        return settings.defaultSpec();
    }

    /** Exceptions thrown by listeners and swallowed so far. A value that keeps growing is a bug to fix. */
    public long listenerFailures() {
        return settings.listenerFailures().get();
    }

    public static final class Builder<Q> {
        private final Map<String, LlmProvider<Q>> providers = new LinkedHashMap<>();
        private ChainSpec defaultSpec;
        private ChainClock clock = ChainClock.system();
        private BreakerConfig breakerConfig = BreakerConfig.defaults();
        private StageBreaker breaker;
        private SharedVerdictStore sharedVerdictStore;
        private HedgePolicy hedge = HedgePolicy.disabled();
        private PriceTable prices = PriceTable.none();
        private RetryPolicy retry = RetryPolicy.defaults();
        private Duration callTimeout = Duration.ZERO;
        private final Map<String, Duration> callTimeoutByProvider = new HashMap<>();
        private ErrorClassifier classifier;
        private OutputValidator validator = OutputValidator.acceptAll();
        private int maxConsecutiveRejections = 2;
        private UsageListener usageListener = UsageListener.NONE;
        private ChainEventListener eventListener = ChainEventListener.NONE;
        private ConcurrencyLimiter limiter = ConcurrencyLimiter.unbounded();
        private AbandonedSpendEstimator<Q> spendEstimator = AbandonedSpendEstimator.none();
        private ContextPropagator propagator = ContextPropagator.NONE;
        private Executor stageExecutor = DEFAULT_STAGE_EXECUTOR;

        private Builder() {
        }

        /** Registers a provider. Names are case-insensitive and must be unique. */
        public Builder<Q> provider(LlmProvider<Q> provider) {
            Objects.requireNonNull(provider, "provider");
            String name = Objects.requireNonNull(provider.name(), "provider name").strip().toLowerCase(Locale.ROOT);
            if (name.isEmpty() || name.contains(",") || name.contains(":")) {
                throw new IllegalArgumentException("invalid provider name '" + provider.name() + "'");
            }
            if (providers.putIfAbsent(name, provider) != null) {
                throw new IllegalArgumentException("duplicate provider name '" + name + "'");
            }
            return this;
        }

        /** The chain used when {@code execute} is called without a spec. Default: providers in order. */
        public Builder<Q> defaultSpec(ChainSpec spec) {
            this.defaultSpec = Objects.requireNonNull(spec, "spec");
            return this;
        }

        public Builder<Q> clock(ChainClock value) {
            this.clock = Objects.requireNonNull(value, "clock");
            return this;
        }

        public Builder<Q> breaker(BreakerConfig config) {
            this.breakerConfig = Objects.requireNonNull(config, "config");
            this.breaker = null;
            return this;
        }

        /** Uses an existing breaker (for example one shared by several chains). Overrides the config. */
        public Builder<Q> breaker(StageBreaker value) {
            this.breaker = Objects.requireNonNull(value, "breaker");
            return this;
        }

        /** Shares breaker verdicts across processes. Ignored when an existing breaker is given. */
        public Builder<Q> sharedVerdictStore(SharedVerdictStore store) {
            this.sharedVerdictStore = Objects.requireNonNull(store, "store");
            return this;
        }

        public Builder<Q> hedging(HedgePolicy policy) {
            this.hedge = Objects.requireNonNull(policy, "policy");
            return this;
        }

        public Builder<Q> priceTable(PriceTable table) {
            this.prices = Objects.requireNonNull(table, "table");
            return this;
        }

        public Builder<Q> retry(RetryPolicy policy) {
            this.retry = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /** Total time cap of every provider call, beyond any socket timeout. Zero (default) = none. */
        public Builder<Q> callTimeout(Duration value) {
            this.callTimeout = nonNegative(value, "callTimeout");
            return this;
        }

        /** Total time cap of the calls of one provider, overriding {@link #callTimeout(Duration)}. */
        public Builder<Q> callTimeout(String provider, Duration value) {
            callTimeoutByProvider.put(provider.strip().toLowerCase(Locale.ROOT), nonNegative(value, "callTimeout"));
            return this;
        }

        public Builder<Q> errorClassifier(ErrorClassifier value) {
            this.classifier = Objects.requireNonNull(value, "classifier");
            return this;
        }

        public Builder<Q> outputValidator(OutputValidator value) {
            this.validator = Objects.requireNonNull(value, "validator");
            return this;
        }

        /** Consecutive output rejections after which the chain stops. Default 2. */
        public Builder<Q> maxConsecutiveRejections(int value) {
            if (value < 1) {
                throw new IllegalArgumentException("maxConsecutiveRejections must be at least 1");
            }
            this.maxConsecutiveRejections = value;
            return this;
        }

        public Builder<Q> usageListener(UsageListener value) {
            this.usageListener = Objects.requireNonNull(value, "listener");
            return this;
        }

        public Builder<Q> eventListener(ChainEventListener value) {
            this.eventListener = Objects.requireNonNull(value, "listener");
            return this;
        }

        public Builder<Q> concurrencyLimiter(ConcurrencyLimiter value) {
            this.limiter = Objects.requireNonNull(value, "limiter");
            return this;
        }

        public Builder<Q> abandonedSpendEstimator(AbandonedSpendEstimator<Q> value) {
            this.spendEstimator = Objects.requireNonNull(value, "estimator");
            return this;
        }

        public Builder<Q> contextPropagator(ContextPropagator value) {
            this.propagator = Objects.requireNonNull(value, "propagator");
            return this;
        }

        /**
         * Executor of the stages. Default: one virtual thread per stage. Overtaken stages keep running
         * until their provider answers, so a bounded pool can starve: size it accordingly.
         */
        public Builder<Q> stageExecutor(Executor value) {
            this.stageExecutor = Objects.requireNonNull(value, "executor");
            return this;
        }

        public ResilientChain<Q> build() {
            if (providers.isEmpty()) {
                throw new IllegalStateException("at least one provider is required");
            }
            ChainSpec spec = defaultSpec != null ? defaultSpec
                    : ChainSpec.ofProviders(new ArrayList<>(providers.keySet()).toArray(String[]::new));
            StageBreaker effectiveBreaker = breaker != null ? breaker
                    : new StageBreaker(breakerConfig, clock, sharedVerdictStore);
            ErrorClassifier effectiveClassifier = classifier != null ? classifier
                    : new DefaultErrorClassifier(clock.asClock());
            return new ResilientChain<>(new ChainSettings<>(
                    Collections.unmodifiableMap(new LinkedHashMap<>(providers)),
                    spec,
                    clock,
                    effectiveBreaker,
                    hedge,
                    prices,
                    retry,
                    callTimeout,
                    Map.copyOf(callTimeoutByProvider),
                    effectiveClassifier,
                    validator,
                    maxConsecutiveRejections,
                    usageListener,
                    eventListener,
                    limiter,
                    spendEstimator,
                    propagator,
                    stageExecutor,
                    new AtomicLong()));
        }

        private static Duration nonNegative(Duration value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isNegative()) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
            return value;
        }
    }
}

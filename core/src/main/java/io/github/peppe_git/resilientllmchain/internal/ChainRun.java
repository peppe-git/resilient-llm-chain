// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.internal;

import io.github.peppe_git.resilientllmchain.AttemptRecord;
import io.github.peppe_git.resilientllmchain.CallOptions;
import io.github.peppe_git.resilientllmchain.CancellationToken;
import io.github.peppe_git.resilientllmchain.ChainCancelledException;
import io.github.peppe_git.resilientllmchain.ChainExhaustedException;
import io.github.peppe_git.resilientllmchain.ChainResult;
import io.github.peppe_git.resilientllmchain.ChainSpec;
import io.github.peppe_git.resilientllmchain.LlmProvider;
import io.github.peppe_git.resilientllmchain.LlmResult;
import io.github.peppe_git.resilientllmchain.NoAvailableStageException;
import io.github.peppe_git.resilientllmchain.Outcome;
import io.github.peppe_git.resilientllmchain.OutputValidator;
import io.github.peppe_git.resilientllmchain.StageContext;
import io.github.peppe_git.resilientllmchain.StageRef;
import io.github.peppe_git.resilientllmchain.Usage;
import io.github.peppe_git.resilientllmchain.breaker.StageBreaker;
import io.github.peppe_git.resilientllmchain.cost.ModelPrice;
import io.github.peppe_git.resilientllmchain.error.CallTimeoutException;
import io.github.peppe_git.resilientllmchain.error.EmptyOutputException;
import io.github.peppe_git.resilientllmchain.error.ErrorClassification;
import io.github.peppe_git.resilientllmchain.error.ErrorKind;
import io.github.peppe_git.resilientllmchain.error.OutputRejectedException;
import io.github.peppe_git.resilientllmchain.event.ChainEventListener;
import io.github.peppe_git.resilientllmchain.event.HedgeSuppression;
import io.github.peppe_git.resilientllmchain.event.SkipReason;
import io.github.peppe_git.resilientllmchain.event.UsageEvent;
import io.github.peppe_git.resilientllmchain.time.ChainClock;
import java.lang.System.Logger.Level;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * One chain call. Internal API.
 *
 * <p>The coordinator (the caller's thread) is an event loop over one queue: stage completions, hedge
 * timers and cancellation. It never waits with a real-time timeout, so the whole behaviour follows the
 * injected {@link ChainClock}. Each stage runs on its own thread from the stage executor.
 *
 * <p>Order of a pass:
 * <ol>
 *   <li>start the next eligible stage (skipping open breakers; a stage that would run next to another
 *       one must pass the cost rule, otherwise it waits in line);</li>
 *   <li>arm the hedge timer if there is a next stage;</li>
 *   <li>wait for an event: a winner returns; a failure starts the next stage at once; a due hedge timer
 *       starts the next stage unless the stream has started or the limiter is saturated.</li>
 * </ol>
 * The winner is decided by a compare-and-set: later valid answers are reported as discarded, and
 * overtaken stages are never interrupted.
 */
public final class ChainRun<Q> {

    private static final System.Logger LOG = System.getLogger(ChainRun.class.getName());
    private static final int ERROR_DESCRIPTION_CHARS = 300;

    private interface Event {
    }

    /** A stage ended. An inner class, not a record, because it refers to the inner {@link Stage}. */
    private final class StageDone implements Event {
        private final Stage run;
        private final Outcome outcome;
        private final LlmResult result;
        private final Throwable failure;
        private final Duration latency;

        StageDone(Stage run, Outcome outcome, LlmResult result, Throwable failure, Duration latency) {
            this.run = run;
            this.outcome = outcome;
            this.result = result;
            this.failure = failure;
            this.latency = latency;
        }

        Stage run() {
            return run;
        }

        Outcome outcome() {
            return outcome;
        }

        LlmResult result() {
            return result;
        }

        Throwable failure() {
            return failure;
        }

        Duration latency() {
            return latency;
        }
    }

    private record HedgeDue(long seq) implements Event {
    }

    private enum Cancelled implements Event { INSTANCE }

    private record Planned<Q>(LlmProvider<Q> provider, StageRef ref) {
    }

    private record Pass(ChainResult result, boolean attempted, boolean skippedByBreaker, Throwable last) {
    }

    /** A thrown failure with its classification, carried out of the retry loop. */
    private static final class StageFailure extends Exception {
        final transient Throwable error;
        final transient ErrorClassification classification;

        StageFailure(Throwable error, ErrorClassification classification) {
            super(null, null, false, false);
            this.error = error;
            this.classification = classification;
        }
    }

    private final ChainSettings<Q> settings;
    private final ChainSpec spec;
    private final Q request;
    private final CallOptions options;
    private final OutputValidator validator;
    private final ChainClock clock;
    private final StageBreaker breaker;
    private final StreamChannel stream;
    private final AtomicBoolean settled = new AtomicBoolean();
    private final LinkedBlockingQueue<Event> events = new LinkedBlockingQueue<>();
    private final List<AttemptRecord> attempts = new CopyOnWriteArrayList<>();

    public ChainRun(ChainSettings<Q> settings, ChainSpec spec, Q request, CallOptions options) {
        this.settings = settings;
        this.spec = spec;
        this.request = request;
        this.options = options;
        this.validator = options.validator() != null ? options.validator() : settings.validator();
        this.clock = settings.clock();
        this.breaker = settings.breaker();
        this.stream = options.stream() == null ? null : new StreamChannel(options.stream(), settings.listenerFailures());
    }

    public ChainResult run() {
        long chainStart = clock.millis();
        CancellationToken.Registration registration =
                options.cancellation().onCancel(() -> events.offer(Cancelled.INSTANCE));
        try {
            List<Planned<Q>> stages = plan();
            if (stages.isEmpty()) {
                throw new NoAvailableStageException("no registered and available provider in chain '" + spec + "'");
            }
            if (options.cancellation().isCancelled()) {
                throw cancelled("cancelled before the first stage", null);
            }
            Pass pass = pass(stages, true, chainStart);
            if (pass.result() != null) {
                return pass.result();
            }
            // Fail-open: the breaker must never fake an outage. If it skipped every stage and nothing was
            // tried, try the chain again ignoring it. If a healthy stage was tried and failed, that real
            // error is what the caller gets.
            if (!pass.attempted() && pass.skippedByBreaker() && !options.cancellation().isCancelled()) {
                notifyEvent(ChainEventListener::onFailOpen);
                pass = pass(stages, false, chainStart);
                if (pass.result() != null) {
                    return pass.result();
                }
            }
            if (options.cancellation().isCancelled()) {
                throw cancelled("cancelled", pass.last());
            }
            throw new ChainExhaustedException("every stage of chain '" + spec + "' failed", pass.last(),
                    List.copyOf(attempts));
        } finally {
            // Nobody will receive an answer after this point: late valid answers are discarded.
            settled.set(true);
            registration.close();
            if (stream != null) {
                stream.close();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Planning

    private List<Planned<Q>> plan() {
        Set<StageRef> seen = new LinkedHashSet<>();
        List<Planned<Q>> out = new ArrayList<>();
        Map<String, LlmProvider<Q>> providers = settings.providers();
        for (ChainSpec.Entry entry : spec.entries()) {
            LlmProvider<Q> provider = providers.get(entry.provider());
            if (provider == null) {
                StageRef ref = new StageRef(entry.provider(), entry.model());
                LOG.log(Level.WARNING, "unknown provider in chain ''{0}'': {1} skipped", spec, ref);
                notifyEvent(l -> l.onStageSkipped(ref, SkipReason.UNKNOWN_PROVIDER));
                continue;
            }
            if (!isAvailable(provider)) {
                StageRef ref = new StageRef(provider.name(),
                        entry.model() != null ? entry.model() : provider.defaultModel());
                notifyEvent(l -> l.onStageSkipped(ref, SkipReason.UNAVAILABLE));
                continue;
            }
            List<String> models = entry.model() != null ? List.of(entry.model()) : modelsOf(provider);
            for (String model : models) {
                StageRef ref = new StageRef(provider.name(), model);
                if (seen.add(ref)) {
                    out.add(new Planned<>(provider, ref));
                }
            }
        }
        return out;
    }

    private static boolean isAvailable(LlmProvider<?> provider) {
        try {
            return provider.available();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Without a pinned model: the provider's default model, then its other models. */
    private static List<String> modelsOf(LlmProvider<?> provider) {
        List<String> models = new ArrayList<>();
        String preferred = provider.defaultModel();
        if (preferred != null && !preferred.isBlank()) {
            models.add(preferred.strip());
        }
        List<String> others = provider.models();
        if (others != null) {
            for (String model : others) {
                if (model != null && !model.isBlank() && !models.contains(model.strip())) {
                    models.add(model.strip());
                }
            }
        }
        if (models.isEmpty()) {
            models.add(null);
        }
        return models;
    }

    // ---------------------------------------------------------------------------------------------
    // Coordinator

    private final class PassState {
        final List<Planned<Q>> stages;
        final boolean honorBreaker;
        final List<Stage> inFlight = new ArrayList<>();
        int next;
        boolean parallelBlocked;
        boolean attempted;
        boolean skippedByBreaker;
        int rejections;
        Throwable last;
        long hedgeSeq;
        ChainClock.Timer hedgeTimer;

        PassState(List<Planned<Q>> stages, boolean honorBreaker) {
            this.stages = stages;
            this.honorBreaker = honorBreaker;
        }

        /** Starts the next eligible stage, if any. */
        void launchOne() {
            while (next < stages.size()) {
                Planned<Q> candidate = stages.get(next++);
                if (honorBreaker && breaker.isOpen(candidate.ref())) {
                    skippedByBreaker = true;
                    notifyEvent(l -> l.onStageSkipped(candidate.ref(), SkipReason.BREAKER_OPEN));
                    continue;
                }
                if (options.cancellation().isCancelled()) {
                    next = stages.size();
                    return;
                }
                // A stage that starts while another is in flight is a parallel start, whatever brought us
                // here (a hedge timer, or a hedged stage failing while the first one still runs): the
                // cost rule applies against every stage in flight. Blocked, it waits in line and still
                // runs as a plain fallback if the stages in flight fail.
                if (!inFlight.isEmpty() && !affordable(candidate)) {
                    next--;
                    parallelBlocked = true;
                    notifyEvent(l -> l.onHedgeSuppressed(candidate.ref(), HedgeSuppression.COST_CAP));
                    return;
                }
                start(candidate, !inFlight.isEmpty());
                attempted = true;
                parallelBlocked = false;
                return;
            }
        }

        /** (Re)arms the hedge timer for the current wait, cancelling the previous one. */
        void armHedge() {
            cancelTimer();
            hedgeSeq++;
            if (inFlight.isEmpty() || next >= stages.size() || parallelBlocked) {
                return;
            }
            Duration delay = settings.hedge().delay(options.isInteractive());
            if (!settings.hedge().isEnabled() || delay.isZero()) {
                return;
            }
            if (stream != null && stream.started()) {
                return;
            }
            long seq = hedgeSeq;
            hedgeTimer = clock.schedule(delay, () -> events.offer(new HedgeDue(seq)));
        }

        void cancelTimer() {
            if (hedgeTimer != null) {
                hedgeTimer.cancel();
                hedgeTimer = null;
            }
        }

        boolean affordable(Planned<Q> candidate) {
            OptionalDouble candidateBasis = basis(candidate.ref());
            for (Stage running : inFlight) {
                OptionalDouble runningBasis = basis(running.ref);
                if (!settings.hedge().allowsCost(runningBasis, candidateBasis)) {
                    LOG.log(Level.INFO, "not starting {0} next to {1}: it costs {2}x (cap {3}x); waiting in line",
                            candidate.ref(), running.ref,
                            String.format(java.util.Locale.ROOT, "%.2f",
                                    candidateBasis.getAsDouble() / runningBasis.getAsDouble()),
                            settings.hedge().maxCostRatio());
                    return false;
                }
            }
            return true;
        }

        void start(Planned<Q> candidate, boolean hedge) {
            Stage run = new Stage(candidate, hedge, clock.millis());
            inFlight.add(run);
            notifyEvent(l -> l.onStageStarted(candidate.ref(), hedge));
            Runnable task = settings.propagator().wrap(() -> events.offer(execute(run)));
            try {
                settings.stageExecutor().execute(task);
            } catch (RejectedExecutionException e) {
                events.offer(new StageDone(run, Outcome.FAILED, null, e, Duration.ZERO));
            }
        }
    }

    private Pass pass(List<Planned<Q>> stages, boolean honorBreaker, long chainStart) {
        PassState st = new PassState(stages, honorBreaker);
        try {
            st.launchOne();
            st.armHedge();
            while (!st.inFlight.isEmpty()) {
                Event event;
                try {
                    event = events.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw cancelled("interrupted while waiting for the stages", e);
                }
                if (event instanceof Cancelled) {
                    throw cancelled("cancelled", st.last);
                }
                if (event instanceof HedgeDue due) {
                    if (due.seq() != st.hedgeSeq || st.next >= stages.size()) {
                        continue; // stale timer
                    }
                    st.hedgeTimer = null;
                    StageRef candidate = stages.get(st.next).ref();
                    if (stream != null && stream.started()) {
                        notifyEvent(l -> l.onHedgeSuppressed(candidate, HedgeSuppression.STREAM_STARTED));
                        continue;
                    }
                    if (settings.limiter().availablePermits() <= 0) {
                        notifyEvent(l -> l.onHedgeSuppressed(candidate, HedgeSuppression.LIMITER_SATURATED));
                        continue;
                    }
                    LOG.log(Level.DEBUG, "no answer within the hedge delay: starting the next stage next to {0}",
                            st.inFlight);
                    st.launchOne();
                    st.armHedge();
                    continue;
                }
                StageDone done = asDone(event);
                if (!st.inFlight.remove(done.run())) {
                    continue;
                }
                attempts.add(new AttemptRecord(done.run().ref, done.run().hedge, done.outcome(), done.latency(),
                        done.failure()));
                switch (done.outcome()) {
                    case WON -> {
                        return new Pass(result(done, chainStart, !honorBreaker), true, st.skippedByBreaker, null);
                    }
                    case REJECTED -> {
                        st.last = done.failure();
                        st.rejections++;
                        // Two different models rejected in a row: the defect is more likely in the prompt
                        // or the validator than in the providers. Going on would only pay for it again.
                        if (st.rejections >= settings.maxConsecutiveRejections() && st.next < stages.size()) {
                            int rejections = st.rejections;
                            int remaining = stages.size() - st.next;
                            LOG.log(Level.WARNING, "{0} consecutive output rejections: chain stopped with {1} "
                                    + "stages untried", rejections, remaining);
                            notifyEvent(l -> l.onStoppedByRejections(rejections, remaining));
                            st.next = stages.size();
                        }
                    }
                    case FAILED -> {
                        st.last = done.failure();
                        // A failure that is not about the format says nothing about it: reset the count.
                        st.rejections = 0;
                    }
                    case DISCARDED -> {
                        // only after another stage won, which has already returned
                    }
                }
                st.launchOne();
                st.armHedge();
            }
            return new Pass(null, st.attempted, st.skippedByBreaker, st.last);
        } finally {
            st.cancelTimer();
        }
    }

    // The only other events are handled above; the cast is unchecked because StageDone is an inner class.
    @SuppressWarnings("unchecked")
    private StageDone asDone(Event event) {
        return (StageDone) event;
    }

    private ChainResult result(StageDone done, long chainStart, boolean failOpen) {
        LlmResult answer = done.result();
        StageRef stage = done.run().ref;
        return new ChainResult(
                answer.text(),
                stage,
                answer.model() != null ? answer.model() : stage.model(),
                answer.usage(),
                done.latency(),
                Duration.ofMillis(clock.millis() - chainStart),
                List.copyOf(attempts),
                failOpen);
    }

    private ChainCancelledException cancelled(String message, Throwable cause) {
        return new ChainCancelledException(message, cause, List.copyOf(attempts));
    }

    // ---------------------------------------------------------------------------------------------
    // Stage execution (stage threads)

    private final class Stage implements StageContext {
        final Planned<Q> plan;
        final StageRef ref;
        final boolean hedge;
        final long startedAt;
        volatile int attempt;

        Stage(Planned<Q> plan, boolean hedge, long startedAt) {
            this.plan = plan;
            this.ref = plan.ref();
            this.hedge = hedge;
            this.startedAt = startedAt;
        }

        @Override
        public StageRef stage() {
            return ref;
        }

        @Override
        public int attempt() {
            return attempt;
        }

        @Override
        public boolean interactive() {
            return options.isInteractive();
        }

        @Override
        public CancellationToken cancellation() {
            return options.cancellation();
        }

        @Override
        public Map<String, String> labels() {
            return options.labels();
        }

        @Override
        public void emit(String text) {
            if (stream != null) {
                stream.delta(this, text);
            }
        }

        @Override
        public String toString() {
            return ref.toString();
        }
    }

    private StageDone execute(Stage run) {
        StageRef ref = run.ref;
        LlmResult result;
        StageBreaker.CallHandle call = breaker.begin(ref);
        try {
            result = callWithRetry(run);
        } catch (StageFailure f) {
            return failed(run, f.error, f.classification);
        } catch (Throwable unexpected) {
            return failed(run, unexpected, ErrorClassification.failure(true));
        } finally {
            call.close();
        }
        Duration latency = since(run.startedAt);
        StageRef answered = result.model() != null && !result.model().equals(ref.model())
                ? new StageRef(ref.provider(), result.model())
                : ref;
        Optional<String> defect = validate(result);
        if (defect.isPresent()) {
            release(run);
            OutputRejectedException rejected = new OutputRejectedException(ref, defect.get());
            LOG.log(Level.WARNING, "stage {0}: output rejected ({1})", ref, defect.get());
            report(answered, ref, Outcome.REJECTED, result.usage(), false, latency, rejected);
            return new StageDone(run, Outcome.REJECTED, result, rejected, latency);
        }
        // The provider answered with a valid output: its health is good, whether or not it wins the race.
        breaker.recordSuccess(ref);
        notifyEvent(l -> l.onStageSucceeded(ref));
        if (!settled.compareAndSet(false, true)) {
            release(run);
            report(answered, ref, Outcome.DISCARDED, result.usage(), false, latency, null);
            return new StageDone(run, Outcome.DISCARDED, result, null, latency);
        }
        report(answered, ref, Outcome.WON, result.usage(), false, latency, null);
        return new StageDone(run, Outcome.WON, result, null, latency);
    }

    private StageDone failed(Stage run, Throwable error, ErrorClassification classification) {
        StageRef ref = run.ref;
        release(run);
        String description = describe(error);
        if (classification.kind() == ErrorKind.PERMANENT) {
            breaker.recordPermanentFailure(ref, description);
            notifyEvent(l -> l.onPermanentFailure(ref, error));
        } else if (classification.kind() != ErrorKind.CONTENT) {
            breaker.recordFailure(ref, description);
        }
        Usage estimate = classification.generationStarted() ? estimate(ref, error) : Usage.ZERO;
        Duration latency = since(run.startedAt);
        report(ref, ref, Outcome.FAILED, estimate, !estimate.isZero(), latency, error);
        LOG.log(Level.INFO, "stage {0} failed after {1} ms: {2}", ref, latency.toMillis(), description);
        return new StageDone(run, Outcome.FAILED, null, error, latency);
    }

    private LlmResult callWithRetry(Stage run) throws StageFailure {
        int maxAttempts = settings.retry().maxAttempts();
        StageFailure last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (attempt > 1 && options.cancellation().isCancelled()) {
                throw last;
            }
            run.attempt = attempt;
            if (stream != null) {
                stream.attemptStarted(run);
            }
            try {
                LlmResult result = invoke(run);
                if (result == null || result.text() == null || result.text().isBlank()) {
                    throw new EmptyOutputException(run.ref);
                }
                return result;
            } catch (Exception e) {
                ErrorClassification classification = classify(e);
                last = new StageFailure(e, classification);
                if (classification.kind() != ErrorKind.RETRYABLE || attempt == maxAttempts) {
                    throw last;
                }
                Duration wait = settings.retry().delayBeforeRetry(attempt, classification.retryAfter());
                LOG.log(Level.DEBUG, "stage {0}: retryable failure on attempt {1}, retrying in {2} ms",
                        run.ref, attempt, wait.toMillis());
                try {
                    clock.sleep(wait);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw last;
                }
            }
        }
        throw last;
    }

    /**
     * One provider call, abandoned if it exceeds the call timeout. The socket read timeout alone does not
     * bound a call: a provider that keeps the connection alive while it thinks never trips it. On timeout
     * the call thread is interrupted, but a blocking read may run on until the provider closes.
     */
    private LlmResult invoke(Stage run) throws Exception {
        LlmProvider<Q> provider = run.plan.provider();
        Duration timeout = settings.callTimeoutFor(run.ref.provider());
        if (timeout == null || timeout.isZero()) {
            return provider.generate(request, run.ref.model(), run);
        }
        CompletableFuture<LlmResult> answer = new CompletableFuture<>();
        // The timer is armed before the call starts, so a test that waits for the call to begin can
        // advance the clock right away.
        ChainClock.Timer timer = clock.schedule(timeout,
                () -> answer.completeExceptionally(new CallTimeoutException(run.ref, timeout)));
        Thread worker = Thread.ofVirtual().name("resilient-llm-chain-call-" + run.ref).unstarted(
                settings.propagator().wrap(() -> {
                    try {
                        answer.complete(provider.generate(request, run.ref.model(), run));
                    } catch (Throwable t) {
                        answer.completeExceptionally(t);
                    }
                }));
        worker.start();
        try {
            return answer.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof CallTimeoutException) {
                worker.interrupt();
            }
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } finally {
            timer.cancel();
        }
    }

    private ErrorClassification classify(Throwable error) {
        try {
            ErrorClassification classification = settings.classifier().classify(error);
            return classification != null ? classification : ErrorClassification.failure(true);
        } catch (RuntimeException e) {
            return ErrorClassification.failure(true);
        }
    }

    private Optional<String> validate(LlmResult result) {
        try {
            Optional<String> defect = validator.defect(result);
            return defect == null ? Optional.empty() : defect;
        } catch (RuntimeException e) {
            return Optional.of("validator failed: " + e);
        }
    }

    private Usage estimate(StageRef ref, Throwable error) {
        try {
            Usage usage = settings.spendEstimator().estimate(request, ref, error);
            return usage == null ? Usage.ZERO : usage;
        } catch (RuntimeException e) {
            return Usage.ZERO;
        }
    }

    private void release(Stage run) {
        if (stream != null) {
            stream.release(run);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Reporting

    private void report(StageRef answered, StageRef planned, Outcome outcome, Usage usage, boolean estimated,
            Duration latency, Throwable failure) {
        OptionalDouble cost = price(answered).or(() -> price(planned))
                .map(p -> OptionalDouble.of(p.cost(usage)))
                .orElse(OptionalDouble.empty());
        UsageEvent event = new UsageEvent(answered, outcome, usage, estimated, cost, latency, failure, options.labels());
        try {
            settings.usageListener().onUsage(event);
        } catch (RuntimeException e) {
            settings.listenerFailures().incrementAndGet();
            LOG.log(Level.DEBUG, "usage listener failed: {0}", e.toString());
        }
    }

    private Optional<ModelPrice> price(StageRef stage) {
        try {
            Optional<ModelPrice> price = settings.prices().price(stage);
            return price == null ? Optional.empty() : price;
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private OptionalDouble basis(StageRef stage) {
        return settings.hedge().basis(price(stage).orElse(null));
    }

    private void notifyEvent(Consumer<ChainEventListener> call) {
        try {
            call.accept(settings.eventListener());
        } catch (RuntimeException e) {
            settings.listenerFailures().incrementAndGet();
            LOG.log(Level.DEBUG, "chain event listener failed: {0}", e.toString());
        }
    }

    private Duration since(long startMillis) {
        return Duration.ofMillis(Math.max(0L, clock.millis() - startMillis));
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        String text = error.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        return text.length() > ERROR_DESCRIPTION_CHARS ? text.substring(0, ERROR_DESCRIPTION_CHARS) + "..." : text;
    }
}

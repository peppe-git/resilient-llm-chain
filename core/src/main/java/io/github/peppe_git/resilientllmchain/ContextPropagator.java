// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

import java.util.Objects;

/**
 * Carries the caller's thread-bound context (logging MDC, tenant id, tracing span) onto the threads that
 * run the stages. {@link #wrap} is called on the caller's thread; the returned task runs on a stage
 * thread and must restore the captured context around {@code task}, and clean it up afterwards.
 *
 * <p>The chain itself keeps no thread-locals: everything it needs travels in {@link CallOptions}.
 */
@FunctionalInterface
public interface ContextPropagator {

    /** Propagates nothing. */
    ContextPropagator NONE = task -> task;

    /** Captures the current context and returns a task that runs {@code task} inside it. */
    Runnable wrap(Runnable task);

    /** Applies this propagator, then {@code next}. */
    default ContextPropagator andThen(ContextPropagator next) {
        Objects.requireNonNull(next, "next");
        return task -> wrap(next.wrap(task));
    }
}

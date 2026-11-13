// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.event;

import io.github.peppe_git.resilientllmchain.StageRef;

/**
 * The chain's decisions, for logs and metrics. Every method has an empty default. Called from the
 * caller's thread or from stage threads; exceptions are counted and swallowed.
 */
public interface ChainEventListener {

    ChainEventListener NONE = new ChainEventListener() { };

    /** A stage of the spec was not attempted. */
    default void onStageSkipped(StageRef stage, SkipReason reason) {
    }

    /** A stage started; {@code hedge} is true when another stage was still in flight. */
    default void onStageStarted(StageRef stage, boolean hedge) {
    }

    /** A hedge was due and did not start. Tells "hedging never fires" apart from "hedging is off". */
    default void onHedgeSuppressed(StageRef candidate, HedgeSuppression reason) {
    }

    /** Every stage was skipped by the breaker: the chain is retried ignoring it. */
    default void onFailOpen() {
    }

    /**
     * The chain stopped after {@code rejections} consecutive output rejections, leaving
     * {@code remainingStages} stages untried.
     */
    default void onStoppedByRejections(int rejections, int remainingStages) {
    }

    /** A stage failed with a permanent error (the model is gone): a hook for catalogue quarantine. */
    default void onPermanentFailure(StageRef stage, Throwable failure) {
    }

    /** A stage answered with a valid output (whether or not it won the race). */
    default void onStageSucceeded(StageRef stage) {
    }
}

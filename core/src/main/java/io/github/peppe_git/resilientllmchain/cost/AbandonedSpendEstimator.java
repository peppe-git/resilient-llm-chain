// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.cost;

import io.github.peppe_git.resilientllmchain.StageRef;
import io.github.peppe_git.resilientllmchain.Usage;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * Estimates the tokens a failed call probably consumed. A call abandoned by a timeout, or cut off
 * mid-body, has usually been generated and billed by the provider: the caller just stopped listening
 * before reading the usage. Without an estimate those calls report zero, which blinds any spend cap
 * exactly on the longest and most expensive calls.
 *
 * <p>Called only when the {@code ErrorClassifier} says the generation had started. Explicit rejections
 * (rate limits, auth, bad requests) cost nothing and are never estimated.
 *
 * @param <Q> the chain's request type
 */
@FunctionalInterface
public interface AbandonedSpendEstimator<Q> {

    /** Tokens probably consumed; {@link Usage#ZERO} when there is no basis for an estimate. */
    Usage estimate(Q request, StageRef stage, Throwable failure);

    /** Estimates nothing: abandoned calls report zero usage. */
    static <Q> AbandonedSpendEstimator<Q> none() {
        return (request, stage, failure) -> Usage.ZERO;
    }

    /**
     * Input tokens from the request size at four characters per token, and a fixed assumption for the
     * output. Use the output cap the providers are configured with: the goal is to stop reporting zero,
     * and the cap is a defensible upper bound for non-reasoning calls.
     *
     * @param promptCharacters    characters sent to the model for a request
     * @param assumedOutputTokens output tokens to assume for an abandoned call
     */
    static <Q> AbandonedSpendEstimator<Q> characterHeuristic(
            ToIntFunction<Q> promptCharacters, long assumedOutputTokens) {
        Objects.requireNonNull(promptCharacters, "promptCharacters");
        if (assumedOutputTokens < 0) {
            throw new IllegalArgumentException("assumedOutputTokens must not be negative");
        }
        return (request, stage, failure) ->
                Usage.of(Math.max(0, promptCharacters.applyAsInt(request)) / 4L, assumedOutputTokens);
    }
}

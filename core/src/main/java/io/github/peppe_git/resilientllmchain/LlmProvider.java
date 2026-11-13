// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

import java.util.List;

/**
 * A provider the chain can call. The request type {@code Q} belongs to the application: each provider
 * adapts it to its vendor's API. Use {@link Prompt} when a system and a user message are enough.
 *
 * <p>Implementations must be thread-safe: the chain calls them concurrently, from virtual threads.
 *
 * @param <Q> the request type shared by every provider of a chain
 */
public interface LlmProvider<Q> {

    /** Unique name, used in chain specs. Compared case-insensitively. */
    String name();

    /**
     * Whether the provider can be called at all (for example: an API key is configured). Unavailable
     * providers are skipped without counting as failures.
     */
    default boolean available() {
        return true;
    }

    /** The model used when a chain spec does not pin one, or {@code null} if the provider has none. */
    default String defaultModel() {
        return null;
    }

    /**
     * Other models of this provider to try, in order, after {@link #defaultModel()} and before moving to
     * the next provider, when the chain spec does not pin a model. Empty by default.
     */
    default List<String> models() {
        return List.of();
    }

    /**
     * Performs one call. Throw on any failure: the chain's {@code ErrorClassifier} decides whether it is
     * retried on this stage, counted by the breaker, or treated as permanent.
     *
     * @param request the application's request
     * @param model   the model to call, or {@code null} when the provider has no model notion
     * @param context per-call context: streaming, cancellation, attempt number
     * @return the generated text with its usage; a {@code null} or blank text is treated as a failure
     */
    LlmResult generate(Q request, String model, StageContext context) throws Exception;
}

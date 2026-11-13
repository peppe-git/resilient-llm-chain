// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.error;

/** What the chain does with a failed provider call. */
public enum ErrorKind {
    /**
     * Transient (server errors, rate limits): retried on the same stage with backoff, honouring
     * {@code Retry-After}; once retries are exhausted it counts as a breaker failure.
     */
    RETRYABLE,
    /**
     * The model will not come back by waiting (not found, retired): no retry, the breaker opens the
     * long cooldown at once.
     */
    PERMANENT,
    /**
     * Depends on this request's content (a safety refusal): no retry, the breaker is not touched, the
     * chain moves on.
     */
    CONTENT,
    /** Anything else (authentication, payment, bad request, network, timeout): no retry, breaker failure. */
    FAILURE
}

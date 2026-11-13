// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.event;

/** Why a hedge that was due did not start. */
public enum HedgeSuppression {
    /** The candidate costs more than {@code maxCostRatio} times a stage in flight. */
    COST_CAP,
    /** The concurrency limiter had no free permit. */
    LIMITER_SATURATED,
    /** The running stage had already streamed text: it is not silent. */
    STREAM_STARTED
}

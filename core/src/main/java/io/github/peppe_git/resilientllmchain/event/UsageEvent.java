// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.event;

import io.github.peppe_git.resilientllmchain.Outcome;
import io.github.peppe_git.resilientllmchain.StageRef;
import io.github.peppe_git.resilientllmchain.Usage;
import java.time.Duration;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * The spend of one stage attempt, reported whatever its outcome: a discarded hedge and an abandoned
 * call bought tokens too.
 *
 * @param stage     the stage (with the model that answered, when the provider reported it)
 * @param outcome   how the attempt ended
 * @param usage     tokens, measured or estimated
 * @param estimated true when {@code usage} is an estimate for an abandoned call
 * @param cost      cost from the {@code PriceTable}, empty when the price is unknown
 * @param latency   duration of the attempt, retries included
 * @param failure   the failure, for {@link Outcome#FAILED} and {@link Outcome#REJECTED}
 * @param labels    the call's labels
 */
public record UsageEvent(
        StageRef stage,
        Outcome outcome,
        Usage usage,
        boolean estimated,
        OptionalDouble cost,
        Duration latency,
        Throwable failure,
        Map<String, String> labels) {
}

// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

import java.time.Duration;

/**
 * One stage attempt of a chain call, for diagnostics.
 *
 * @param stage   the stage
 * @param hedge   true if the stage was started while another stage was still in flight
 * @param outcome how it ended
 * @param latency time from the stage start to its end
 * @param failure the failure, for {@link Outcome#FAILED} and {@link Outcome#REJECTED}; otherwise null
 */
public record AttemptRecord(StageRef stage, boolean hedge, Outcome outcome, Duration latency, Throwable failure) {
}

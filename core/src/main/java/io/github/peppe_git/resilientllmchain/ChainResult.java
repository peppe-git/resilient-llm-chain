// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

import java.time.Duration;
import java.util.List;

/**
 * The answer of a chain call.
 *
 * @param text     the winning text
 * @param stage    the stage that won
 * @param model    the model that answered (as reported by the provider, else the stage model)
 * @param usage    tokens of the winning call
 * @param latency  duration of the winning stage
 * @param elapsed  duration of the whole chain call
 * @param attempts stage attempts that ended before the call returned, in completion order
 * @param failOpen true if the answer came from the fail-open pass (every stage was cooling down)
 */
public record ChainResult(
        String text,
        StageRef stage,
        String model,
        Usage usage,
        Duration latency,
        Duration elapsed,
        List<AttemptRecord> attempts,
        boolean failOpen) {

    public ChainResult {
        attempts = List.copyOf(attempts);
    }
}

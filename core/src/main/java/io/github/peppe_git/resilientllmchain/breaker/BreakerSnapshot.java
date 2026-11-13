// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.breaker;

import io.github.peppe_git.resilientllmchain.StageRef;
import java.time.Instant;

/**
 * Read-only state of one stage, for dashboards and alerts.
 *
 * @param stage               the stage
 * @param status              current status
 * @param consecutiveFailures failures since the last success
 * @param lastError           short description of the last failure, or null
 * @param lastErrorAt         when it happened, or null
 * @param openUntil           end of the cooldown, or null if none was ever opened
 * @param stalledCalls        calls in flight beyond {@code stallAfter} right now
 * @param lastOpenedAt        last time this process opened the cooldown, or null. Alerting should read
 *                            "opened since I last looked" from this: sampling "is it open now" can miss
 *                            a whole cooldown between two samples.
 */
public record BreakerSnapshot(
        StageRef stage,
        BreakerStatus status,
        int consecutiveFailures,
        String lastError,
        Instant lastErrorAt,
        Instant openUntil,
        int stalledCalls,
        Instant lastOpenedAt) {
}

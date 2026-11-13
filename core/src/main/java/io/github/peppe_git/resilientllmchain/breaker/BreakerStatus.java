// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.breaker;

/** State of one stage's breaker. */
public enum BreakerStatus {
    /** Calls go through. */
    CLOSED,
    /** In cooldown: the stage is skipped. */
    OPEN,
    /** Cooldown expired: the next call tries the stage; a failure reopens the cooldown. */
    HALF_OPEN,
    /** No cooldown, but enough calls stuck in flight to treat the stage as open. */
    STALLED
}

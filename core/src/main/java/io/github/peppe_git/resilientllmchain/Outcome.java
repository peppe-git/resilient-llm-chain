// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

/** How one stage attempt ended. */
public enum Outcome {
    /** Produced the answer returned to the caller. */
    WON,
    /** Produced a valid answer after another stage had already won (hedging); its spend is real. */
    DISCARDED,
    /** Answered, but the output validator rejected the answer. */
    REJECTED,
    /** Failed: error, timeout, empty answer. */
    FAILED
}

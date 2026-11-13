// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.event;

/** Why a stage of the spec was not attempted. */
public enum SkipReason {
    /** The breaker is open: cooldown after failures, shared verdict, or stalled calls. */
    BREAKER_OPEN,
    /** The provider reported {@code available() == false}. */
    UNAVAILABLE,
    /** The spec names a provider that is not registered. */
    UNKNOWN_PROVIDER
}

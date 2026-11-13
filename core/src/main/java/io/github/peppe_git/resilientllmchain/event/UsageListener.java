// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.event;

/**
 * Receives one {@link UsageEvent} per stage attempt, on the stage's thread. Best-effort: an exception
 * thrown here is counted and swallowed, never turned into a failed generation that was already paid
 * for. Keep it fast; hand slow work (database writes) to a queue.
 */
@FunctionalInterface
public interface UsageListener {

    UsageListener NONE = event -> { };

    void onUsage(UsageEvent event);
}

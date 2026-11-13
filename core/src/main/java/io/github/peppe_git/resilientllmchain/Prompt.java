// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

/**
 * A ready-made request type for the common case: one system instruction and one user message.
 *
 * @param system system instruction, may be {@code null}
 * @param user   user message
 */
public record Prompt(String system, String user) {

    public static Prompt of(String user) {
        return new Prompt(null, user);
    }

    /** Characters sent to the model, for heuristic token estimates. */
    public int characters() {
        return (system == null ? 0 : system.length()) + (user == null ? 0 : user.length());
    }
}

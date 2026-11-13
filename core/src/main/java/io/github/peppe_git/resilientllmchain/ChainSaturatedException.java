// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

import java.util.List;

/**
 * An interactive call could not get a concurrency permit within its bounded wait. Meant to become a
 * "busy, retry shortly" answer rather than an indefinite wait.
 */
public final class ChainSaturatedException extends ChainException {

    public ChainSaturatedException(String message) {
        super(message, null, List.of());
    }
}

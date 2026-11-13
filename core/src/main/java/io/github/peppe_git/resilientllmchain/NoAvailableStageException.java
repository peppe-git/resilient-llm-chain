// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

import java.util.List;

/** No stage could be attempted: unknown or unavailable providers only. A configuration problem. */
public final class NoAvailableStageException extends ChainException {

    public NoAvailableStageException(String message) {
        super(message, null, List.of());
    }
}

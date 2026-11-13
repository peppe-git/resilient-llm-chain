// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

import java.util.List;

/** The call was cancelled through its {@link CancellationToken}, or the calling thread was interrupted. */
public final class ChainCancelledException extends ChainException {

    public ChainCancelledException(String message, Throwable cause, List<AttemptRecord> attempts) {
        super(message, cause, attempts);
    }
}

// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

import java.util.List;

/**
 * Every stage that was tried failed. The cause is the last failure seen; when the chain stopped because
 * of consecutive output rejections it is an {@code OutputRejectedException}.
 */
public final class ChainExhaustedException extends ChainException {

    public ChainExhaustedException(String message, Throwable cause, List<AttemptRecord> attempts) {
        super(message, cause, attempts);
    }
}

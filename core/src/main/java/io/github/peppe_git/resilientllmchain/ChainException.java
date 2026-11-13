// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

import java.util.List;

/** Base class of the exceptions a chain call throws. Carries the stage attempts that ended. */
public class ChainException extends RuntimeException {

    private final transient List<AttemptRecord> attempts;

    public ChainException(String message, Throwable cause, List<AttemptRecord> attempts) {
        super(message, cause);
        this.attempts = attempts == null ? List.of() : List.copyOf(attempts);
    }

    /** Stage attempts that ended before the exception, in completion order. */
    public List<AttemptRecord> attempts() {
        return attempts;
    }
}

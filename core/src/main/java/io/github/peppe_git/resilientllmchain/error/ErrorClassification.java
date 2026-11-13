// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.error;

import java.time.Duration;
import java.util.Objects;

/**
 * The verdict of an {@link ErrorClassifier} on one failure.
 *
 * @param kind              what to do with it
 * @param retryAfter        wait requested by the provider (only meaningful for {@link ErrorKind#RETRYABLE}),
 *                          or {@code null}; the chain caps it with {@code RetryPolicy.maxRetryAfter}
 * @param generationStarted true if the provider probably generated (and billed) tokens before the
 *                          failure, as with a read timeout or a truncated body; false for explicit
 *                          rejections and failures before the request was sent. Drives the abandoned
 *                          spend estimate.
 */
public record ErrorClassification(ErrorKind kind, Duration retryAfter, boolean generationStarted) {

    public ErrorClassification {
        Objects.requireNonNull(kind, "kind");
        if (retryAfter != null && retryAfter.isNegative()) {
            retryAfter = Duration.ZERO;
        }
    }

    public static ErrorClassification retryable(Duration retryAfter) {
        return new ErrorClassification(ErrorKind.RETRYABLE, retryAfter, false);
    }

    public static ErrorClassification permanent() {
        return new ErrorClassification(ErrorKind.PERMANENT, null, false);
    }

    public static ErrorClassification content(boolean generationStarted) {
        return new ErrorClassification(ErrorKind.CONTENT, null, generationStarted);
    }

    public static ErrorClassification failure(boolean generationStarted) {
        return new ErrorClassification(ErrorKind.FAILURE, null, generationStarted);
    }
}

// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.error;

/**
 * Maps a provider failure to an {@link ErrorClassification}. This is where transport knowledge lives:
 * the core never inspects HTTP status codes or SDK exception types itself.
 *
 * <p>Must not throw; a classifier that throws is treated as answering {@code failure(true)}.
 */
@FunctionalInterface
public interface ErrorClassifier {

    ErrorClassification classify(Throwable failure);

    /** The default classifier: see {@link DefaultErrorClassifier}. */
    static ErrorClassifier defaults() {
        return DefaultErrorClassifier.INSTANCE;
    }
}

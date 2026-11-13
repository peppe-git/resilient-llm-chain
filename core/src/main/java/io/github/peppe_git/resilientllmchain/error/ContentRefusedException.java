// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.error;

/**
 * The provider declined this request because of its content (a safety classifier, for example). The
 * chain moves on without touching the breaker: the refusal says nothing about the provider's health.
 */
public class ContentRefusedException extends RuntimeException {

    private final boolean generationStarted;

    public ContentRefusedException(String message) {
        this(message, true);
    }

    /**
     * @param generationStarted whether tokens were probably billed before the refusal
     */
    public ContentRefusedException(String message, boolean generationStarted) {
        super(message);
        this.generationStarted = generationStarted;
    }

    public boolean generationStarted() {
        return generationStarted;
    }
}

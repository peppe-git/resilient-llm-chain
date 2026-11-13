// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

import java.util.Optional;

/**
 * Checks an answer before it can win. A rejected answer counts as a failed stage for the chain (the
 * next stage is tried) but leaves the breaker untouched: the provider is up, the content is wrong.
 * After {@code maxConsecutiveRejections} rejections in a row the chain stops, because the defect is
 * then more likely in the prompt or in the validator than in the providers.
 */
@FunctionalInterface
public interface OutputValidator {

    /** A description of what is wrong with the answer, or empty if it is acceptable. */
    Optional<String> defect(LlmResult result);

    /** Accepts everything. */
    static OutputValidator acceptAll() {
        return result -> Optional.empty();
    }
}

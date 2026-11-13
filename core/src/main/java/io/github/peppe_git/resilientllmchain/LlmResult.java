// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

/**
 * What a provider returns.
 *
 * @param text  generated text
 * @param model model that actually answered, as reported by the vendor; {@code null} = the requested one
 * @param usage tokens consumed; {@link Usage#ZERO} when unknown
 */
public record LlmResult(String text, String model, Usage usage) {

    public LlmResult {
        usage = usage == null ? Usage.ZERO : usage;
    }

    public static LlmResult of(String text, String model, long inputTokens, long outputTokens) {
        return new LlmResult(text, model, Usage.of(inputTokens, outputTokens));
    }

    public static LlmResult of(String text) {
        return new LlmResult(text, null, Usage.ZERO);
    }
}

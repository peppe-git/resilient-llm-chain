// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

/**
 * Tokens consumed by one call.
 *
 * @param inputTokens      input tokens billed at the full input price (cache misses)
 * @param outputTokens     generated tokens, reasoning included
 * @param cacheReadTokens  input tokens read from a prompt cache
 * @param cacheWriteTokens input tokens written to a prompt cache
 */
public record Usage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {

    public static final Usage ZERO = new Usage(0, 0, 0, 0);

    public Usage {
        if (inputTokens < 0 || outputTokens < 0 || cacheReadTokens < 0 || cacheWriteTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
    }

    public static Usage of(long inputTokens, long outputTokens) {
        return new Usage(inputTokens, outputTokens, 0, 0);
    }

    /** Every input token the model saw, cached or not. */
    public long totalInputTokens() {
        return inputTokens + cacheReadTokens + cacheWriteTokens;
    }

    public boolean isZero() {
        return inputTokens == 0 && outputTokens == 0 && cacheReadTokens == 0 && cacheWriteTokens == 0;
    }
}

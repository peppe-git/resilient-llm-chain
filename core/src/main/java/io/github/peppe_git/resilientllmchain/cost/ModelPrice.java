// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.cost;

import io.github.peppe_git.resilientllmchain.Usage;

/**
 * Price of a model per million tokens, in whatever currency or unit the application uses. The chain only
 * compares prices and adds them up; it never assumes a currency.
 *
 * @param inputPerMillion      price of one million uncached input tokens
 * @param outputPerMillion     price of one million output tokens
 * @param cacheReadPerMillion  price of one million input tokens read from a prompt cache
 * @param cacheWritePerMillion price of one million input tokens written to a prompt cache
 */
public record ModelPrice(
        double inputPerMillion, double outputPerMillion, double cacheReadPerMillion, double cacheWritePerMillion) {

    public ModelPrice {
        if (inputPerMillion < 0 || outputPerMillion < 0 || cacheReadPerMillion < 0 || cacheWritePerMillion < 0
                || Double.isNaN(inputPerMillion + outputPerMillion + cacheReadPerMillion + cacheWritePerMillion)) {
            throw new IllegalArgumentException("prices must be non-negative numbers");
        }
    }

    /** Input and output prices; cached tokens priced like input. */
    public static ModelPrice perMillion(double input, double output) {
        return new ModelPrice(input, output, input, input);
    }

    /** Cost of {@code usage} at this price. */
    public double cost(Usage usage) {
        return (usage.inputTokens() * inputPerMillion
                + usage.outputTokens() * outputPerMillion
                + usage.cacheReadTokens() * cacheReadPerMillion
                + usage.cacheWriteTokens() * cacheWritePerMillion) / 1_000_000d;
    }

    public boolean isFree() {
        return inputPerMillion == 0 && outputPerMillion == 0 && cacheReadPerMillion == 0 && cacheWritePerMillion == 0;
    }
}

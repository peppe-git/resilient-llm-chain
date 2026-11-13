// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.cost;

import io.github.peppe_git.resilientllmchain.StageRef;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Prices of the stages, used by cost-aware hedging and by the cost reported to the
 * {@code UsageListener}. The library ships no prices: they are the application's data.
 *
 * <p>An unknown price is not zero: rules that need a price stay silent when it is missing.
 */
@FunctionalInterface
public interface PriceTable {

    /** The price of {@code stage}, or empty when unknown. Called on hot paths: keep it in memory. */
    Optional<ModelPrice> price(StageRef stage);

    /** No prices at all. */
    static PriceTable none() {
        return stage -> Optional.empty();
    }

    static Builder builder() {
        return new Builder();
    }

    /** An in-memory table keyed by provider and model, with optional per-provider fallbacks. */
    final class Builder {
        private final Map<String, ModelPrice> prices = new HashMap<>();

        private Builder() {
        }

        /** Price of {@code model} on {@code provider}. */
        public Builder price(String provider, String model, ModelPrice price) {
            prices.put(new StageRef(provider, Objects.requireNonNull(model, "model")).key(),
                    Objects.requireNonNull(price, "price"));
            return this;
        }

        /** Price of every model of {@code provider} that has no specific entry. */
        public Builder providerDefault(String provider, ModelPrice price) {
            prices.put(provider.strip().toLowerCase(Locale.ROOT) + "|*", Objects.requireNonNull(price, "price"));
            return this;
        }

        public PriceTable build() {
            Map<String, ModelPrice> snapshot = Map.copyOf(prices);
            return stage -> {
                ModelPrice exact = snapshot.get(stage.key());
                return Optional.ofNullable(exact != null ? exact : snapshot.get(stage.provider() + "|*"));
            };
        }
    }
}

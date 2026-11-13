// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

import java.util.Locale;
import java.util.Objects;

/**
 * One concrete stage of a chain: a provider and the model it will be called with.
 *
 * @param provider provider name, lower case
 * @param model    model the provider is called with, or {@code null} when the provider declares no
 *                 default model and none was pinned
 */
public record StageRef(String provider, String model) {

    public StageRef {
        Objects.requireNonNull(provider, "provider");
        provider = provider.strip().toLowerCase(Locale.ROOT);
        if (provider.isEmpty()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        model = model == null || model.isBlank() ? null : model.strip();
    }

    /** Stable key of this stage for the breaker and the shared verdict store: {@code provider|model}. */
    public String key() {
        return provider + "|" + (model == null ? "" : model);
    }

    @Override
    public String toString() {
        return model == null ? provider : provider + ":" + model;
    }
}

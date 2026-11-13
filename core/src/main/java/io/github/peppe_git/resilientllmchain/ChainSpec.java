// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The ordered list of providers a call should try, written as {@code "provider[:model], ..."}.
 *
 * <p>Each entry names a provider and, optionally, pins a model with {@code provider:model}. Everything
 * after the first {@code :} is the model, so model ids that contain colons are preserved. Provider
 * names are case-insensitive; blank entries are ignored; duplicate entries (same provider and model)
 * are dropped, keeping the first occurrence.
 *
 * <p>This class is syntax only. Resolution against the registered providers happens when the chain
 * runs: an unknown provider is skipped and reported, so a spec that names a removed provider degrades
 * instead of failing.
 */
public final class ChainSpec {

    /**
     * One entry of a spec.
     *
     * @param provider provider name, lower case
     * @param model    pinned model, or {@code null} to use the provider's own model list
     */
    public record Entry(String provider, String model) {

        public Entry {
            Objects.requireNonNull(provider, "provider");
            provider = provider.strip().toLowerCase(Locale.ROOT);
            if (provider.isEmpty()) {
                throw new IllegalArgumentException("provider must not be blank");
            }
            model = model == null || model.isBlank() ? null : model.strip();
        }

        @Override
        public String toString() {
            return model == null ? provider : provider + ":" + model;
        }
    }

    private final List<Entry> entries;

    private ChainSpec(List<Entry> entries) {
        List<Entry> unique = new ArrayList<>();
        for (Entry entry : entries) {
            if (!unique.contains(entry)) {
                unique.add(entry);
            }
        }
        this.entries = List.copyOf(unique);
    }

    /** Parses {@code "a:model-x, b, c:model-y"}. A {@code null} or blank spec yields an empty chain. */
    public static ChainSpec parse(String spec) {
        List<Entry> entries = new ArrayList<>();
        if (spec != null) {
            for (String token : spec.split(",")) {
                String entry = token.strip();
                if (entry.isEmpty()) {
                    continue;
                }
                String[] parts = entry.split(":", 2);
                String provider = parts[0].strip();
                if (provider.isEmpty()) {
                    continue;
                }
                entries.add(new Entry(provider, parts.length > 1 ? parts[1] : null));
            }
        }
        return new ChainSpec(entries);
    }

    /** A spec made of the given entries, in order. */
    public static ChainSpec of(List<Entry> entries) {
        return new ChainSpec(Objects.requireNonNull(entries, "entries"));
    }

    /** A spec that tries the given providers in order, each with its own model list. */
    public static ChainSpec ofProviders(String... providers) {
        return new ChainSpec(Arrays.stream(providers).map(p -> new Entry(p, null)).toList());
    }

    public List<Entry> entries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ChainSpec other && entries.equals(other.entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    /** The canonical form, which {@link #parse} reads back to an equal spec. */
    @Override
    public String toString() {
        return entries.stream().map(Entry::toString).collect(Collectors.joining(","));
    }
}

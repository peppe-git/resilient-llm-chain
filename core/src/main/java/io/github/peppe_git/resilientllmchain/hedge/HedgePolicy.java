// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.hedge;

import io.github.peppe_git.resilientllmchain.cost.ModelPrice;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * When to start the next stage next to a silent one, instead of waiting in line behind it.
 *
 * <p>A serial fallback chain only reaches the next stage after the current one gives up. When a
 * provider accepts the connection and then says nothing, the caller pays the whole call timeout before
 * the alternative is even tried. With hedging, once the running stage has been silent for
 * {@link #delay(boolean)}, the next stage starts too and whichever answers first wins. The order of the
 * chain never changes: the hedge is always the next stage, and if the running stage answers first it
 * wins.
 *
 * <p>Three conditions are evaluated by the chain when the hedge timer fires, and any of them keeps the
 * call in line:
 * <ul>
 *   <li>the running stage has already streamed text: it is not silent, it is writing a long answer;</li>
 *   <li>the concurrency limiter has no free permit: under saturation a hedge adds pressure exactly
 *       where there is the least headroom;</li>
 *   <li>the candidate costs more than {@link #maxCostRatio()} times a stage in flight
 *       ({@link #allowsCost}): a chain ordered by quality puts the expensive model next, and hedging it
 *       against a cheap stage that would have answered pays twice for nothing.</li>
 * </ul>
 *
 * <p>There is <b>no default delay</b>. The right value sits above the normal latency of the running
 * stage and well below its timeout; it is a property of the application's traffic. See the tuning
 * guide in {@code docs/guides/hedge-tuning.md}.
 */
public final class HedgePolicy {

    /** Default cap on the price ratio between the hedge and a stage in flight. */
    public static final double DEFAULT_MAX_COST_RATIO = 2.0;

    private static final long DEFAULT_BASKET_TOKENS = 1_000_000L;

    private static final HedgePolicy DISABLED = new HedgePolicy(
            Duration.ZERO, Duration.ZERO, DEFAULT_MAX_COST_RATIO, DEFAULT_BASKET_TOKENS, DEFAULT_BASKET_TOKENS);

    private final Duration backgroundDelay;
    private final Duration interactiveDelay;
    private final double maxCostRatio;
    private final long basketInputTokens;
    private final long basketOutputTokens;

    private HedgePolicy(Duration background, Duration interactive, double maxCostRatio,
            long basketInputTokens, long basketOutputTokens) {
        this.backgroundDelay = background;
        // An interactive delay longer than the background one would make a person wait longer than work
        // nobody watches: take the smaller one. Zero stays zero, which is how hedging is switched off.
        if (background.isZero() || interactive.isZero()) {
            this.interactiveDelay = Duration.ZERO;
        } else {
            this.interactiveDelay = interactive.compareTo(background) > 0 ? background : interactive;
        }
        this.maxCostRatio = maxCostRatio;
        this.basketInputTokens = basketInputTokens;
        this.basketOutputTokens = basketOutputTokens;
    }

    /** No hedging: the chain is strictly serial. The default. */
    public static HedgePolicy disabled() {
        return DISABLED;
    }

    /** Hedging after {@code delay} for every call, with the default cost cap. */
    public static HedgePolicy after(Duration delay) {
        return builder().backgroundDelay(delay).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** True when at least background calls can be hedged. */
    public boolean isEnabled() {
        return !backgroundDelay.isZero();
    }

    /**
     * The silence after which the next stage is started. {@code interactive} selects the delay for calls
     * someone is waiting on. Zero means no hedging for that kind of call.
     */
    public Duration delay(boolean interactive) {
        return interactive ? interactiveDelay : backgroundDelay;
    }

    /** Cap on the price ratio between a hedge and a stage in flight; {@code <= 0} means no cap. */
    public double maxCostRatio() {
        return maxCostRatio;
    }

    /**
     * The price of the comparison basket at {@code price}: the same number of tokens for every model, so
     * the ratio depends on prices only and not on how long this answer will turn out to be, which is not
     * known when the decision is taken. Empty for an unknown ({@code null}) or free price.
     */
    public OptionalDouble basis(ModelPrice price) {
        if (price == null || price.isFree()) {
            return OptionalDouble.empty();
        }
        double value = (basketInputTokens * price.inputPerMillion()
                + basketOutputTokens * price.outputPerMillion()) / 1_000_000d;
        return value > 0 ? OptionalDouble.of(value) : OptionalDouble.empty();
    }

    /**
     * True if a stage with price basis {@code candidateBasis} may run next to one with
     * {@code runningBasis}. Every edge case is permissive on purpose: no cap configured, an unknown or
     * free price on either side. This rule protects against paying much more to lose a race, not
     * against paying.
     */
    public boolean allowsCost(OptionalDouble runningBasis, OptionalDouble candidateBasis) {
        if (maxCostRatio <= 0 || runningBasis.isEmpty() || candidateBasis.isEmpty()) {
            return true;
        }
        return candidateBasis.getAsDouble() <= runningBasis.getAsDouble() * maxCostRatio;
    }

    @Override
    public String toString() {
        return "HedgePolicy[background=" + backgroundDelay + ", interactive=" + interactiveDelay
                + ", maxCostRatio=" + maxCostRatio + "]";
    }

    public static final class Builder {
        private Duration backgroundDelay = Duration.ZERO;
        private Duration interactiveDelay;
        private double maxCostRatio = DEFAULT_MAX_COST_RATIO;
        private long basketInputTokens = DEFAULT_BASKET_TOKENS;
        private long basketOutputTokens = DEFAULT_BASKET_TOKENS;

        private Builder() {
        }

        /** Delay for calls nobody is waiting on. Zero disables hedging altogether. */
        public Builder backgroundDelay(Duration value) {
            this.backgroundDelay = nonNegative(value, "backgroundDelay");
            return this;
        }

        /**
         * Delay for interactive calls. Defaults to the background delay; clamped to it if longer; zero
         * disables hedging for interactive calls only.
         */
        public Builder interactiveDelay(Duration value) {
            this.interactiveDelay = nonNegative(value, "interactiveDelay");
            return this;
        }

        /** Cap on the price ratio; {@code <= 0} disables the cost rule. Default 2.0. */
        public Builder maxCostRatio(double value) {
            if (Double.isNaN(value)) {
                throw new IllegalArgumentException("maxCostRatio must be a number");
            }
            this.maxCostRatio = value;
            return this;
        }

        /**
         * Tokens of the basket prices are compared on. Default one million input and one million output
         * tokens. Shift it towards your real input/output mix if prices differ a lot between the two.
         */
        public Builder costBasket(long inputTokens, long outputTokens) {
            if (inputTokens < 0 || outputTokens < 0 || inputTokens + outputTokens == 0) {
                throw new IllegalArgumentException("the basket needs a positive number of tokens");
            }
            this.basketInputTokens = inputTokens;
            this.basketOutputTokens = outputTokens;
            return this;
        }

        public HedgePolicy build() {
            Duration interactive = interactiveDelay != null ? interactiveDelay : backgroundDelay;
            return new HedgePolicy(backgroundDelay, interactive, maxCostRatio, basketInputTokens, basketOutputTokens);
        }

        private static Duration nonNegative(Duration value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isNegative()) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
            return value;
        }
    }
}

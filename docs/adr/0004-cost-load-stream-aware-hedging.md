# ADR-0004: Hedging is cost-, load- and stream-aware, and off by default

- Status: accepted
- Date: v0.1

## Context

A serial fallback chain only reaches the next provider after the current one gives up. When a
provider accepts connections and then says nothing, the caller pays the full timeout before the
alternative is even tried. Hedging (launch the next stage after a delay, take whichever answers
first) fixes the tail, but a naive hedge has three failure modes:

1. **Cost.** If the chain is ordered by quality, the next stage is often the expensive one. Hedging
   it against a cheap primary that would have answered pays twice for nothing.
2. **Load.** Under saturation, extra calls add pressure exactly when there is the least headroom.
3. **Streaming.** A stage that has already streamed its first token is not silent; it is writing a
   long answer. Hedging it pays for two long generations.

## Decision

A hedge is launched only when all of these hold at the moment the hedge timer fires:

- hedging is enabled and the delay for this call is non-zero (interactive and background delays are
  distinct; the interactive one is clamped to never exceed the background one);
- there is a next stage that the breaker does not skip;
- the call's stream, if any, has not emitted anything yet;
- the concurrency limiter reports at least one free permit (a heuristic check, not a reservation);
- the candidate's price basis is at most `maxCostRatio` × the price basis of **every** stage in
  flight. When a price is unknown the rule stays silent, so unpriced or free stages never block a
  hedge.

The same cost rule also applies when a stage starts while another is in flight for another reason,
for example a hedged stage failing while the primary is still running.

There is **no default delay**: `HedgePolicy` is disabled until a delay is configured. The right delay
is a property of the user's latency distribution (see `docs/guides/hedge-tuning.md`).

## Consequences

- Misconfiguration cannot silently double spend, because the cost cap is on by default (2.0) once
  hedging is enabled.
- Every suppressed hedge is reported to `ChainEventListener.onHedgeSuppressed` with its reason, so
  "hedging never fires" can be told apart from "hedging is off".

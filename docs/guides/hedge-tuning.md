# Tuning the hedge delay

There is no default hedge delay, on purpose. The right value is a property of your traffic: the latency
distribution of each stage, the price ratio between adjacent stages, and how much extra spend you
accept. This guide gives a method, the formulas behind it, and a worked example on **synthetic**
numbers. Do not copy the example's values: measure your own.

## 1. What to measure

Collect at least a few hundred successful calls per stage, with a `UsageListener` (`latency`,
`outcome`, `cost`). Keep calls that someone waits on apart from background work. For each stage `i`,
record the following.

| Symbol | Meaning |
|---|---|
| `L_i` | Latency of successful calls. For streaming calls, use the **time to the first piece**: once a stage has streamed anything it is never hedged, so only the silence before the first piece matters. |
| `F_i(t)` | Its empirical distribution, `P(L_i <= t)`. |
| `q_i(p)` | Its quantiles: p50, p95, p99. |
| `c_i` | Its price basis, as computed by `HedgePolicy.basis(price)`. |
| `r = c_next / c_running` | The price ratio between the next stage and the running one. |

Measure latency with the reasoning settings and output sizes you actually use: both move the tail by
large factors.

## 2. The formulas

With delay `d` on the running stage 1, and stage 2 next.

**How often a hedge starts.** Ignoring failures, which fall back immediately anyway:

```
h(d) = P(L_1 > d) = 1 - F_1(d)
```

**Extra spend**, as a fraction of the spend without hedging. Overtaken stages are not interrupted, so
both calls are paid in full. That gives an upper bound:

```
extra(d) <= h(d) * r
```

When the hedge wins and the first stage's late answer is short, the real figure is a bit lower.

**Latency.** If the hedge always starts when due, the observed latency is

```
L(d) = min(L_1, d + L_2)
```

and for a quantile `p`, approximately `q_L(p) ~ min(q_1(p), d + q_2(p))` when stage 2 is fast and
reliable. The hedge helps a quantile only if `d + q_2(p) < q_1(p)`.

**Load.** With an arrival rate `lambda` of calls on this chain, hedges add

```
extra concurrency ~ lambda * h(d) * E[L_2]
```

Size the `ConcurrencyLimiter` and the providers' rate limits with that in mind. Under saturation, hedges
are suppressed (`LIMITER_SATURATED`).

## 3. Choosing `d`

Two constraints, one for each side of the trade-off.

**Cost**, for background work. With an extra-spend budget `B` (for example 10%):

```
h(d) * r <= B   =>   F_1(d) >= 1 - B / r   =>   d >= q_1(1 - B / r)
```

**Latency**, for interactive work. With a target `T` on the perceived quantile `p` (for example p99 under
15 s):

```
d + q_2(p) <= T   =>   d <= T - q_2(p)
```

**Bounds** that must hold anyway:
- `d > 0`;
- `d` well above `q_1(0.5)`: below the median you pay two generations on most calls and throw away the
  reason the primary is primary;
- `d < callTimeout`: otherwise the plain fallback after the timeout comes first.

Typically, `stallAfter` sits between the two: `d < stallAfter < callTimeout`.
- The hedge rescues the **current** call.
- The stall detection protects the **next** calls.
- The call timeout bounds the wait.

Then pick each delay:
- **Background delay:** the smallest `d` that meets the cost constraint.
- **Interactive delay:** the largest `d` that meets the latency constraint, if it also fits an
  acceptable cost on the (usually smaller) interactive volume. If it does not, choose between the
  latency target and the budget; the formulas tell you the price of each.

## 4. Choosing `maxCostRatio`

Compute `r` for every pair of adjacent stages in your specs.
- **Pairs where hedging helps** usually have a fast, cheap-enough next stage: `r` close to or below 1.
- **Pairs where it hurts** come from quality-ordered chains, where the next stage is the expensive
  model: `r` of 3 or more. Such a hedge starts late against a running stage that is often faster, so it
  rarely wins and is paid in full when it loses.

Put `maxCostRatio` in the gap between the two groups (the default is 2.0), and note how much margin
the pair you most want to keep has. Prices change: when a price moves, every ratio that has it in the
denominator moves too, and a hedge you rely on can switch off silently. Watch `COST_CAP` suppressions
in `ChainEventListener.onHedgeSuppressed`.

## 5. Checking it in production

From the usage and chain events, track:

| Metric | How | Question it answers |
|---|---|---|
| Hedge rate | `onStageStarted(stage, hedge = true)` / calls | Does it match `h(d)`? |
| Hedge win rate | `WON` events on hedged stages / hedges started | Below a few percent, hedges mostly cost: raise `d` or lower the ratio cap. |
| Discarded spend | sum of `cost` on `DISCARDED` events | Does it match `extra(d)`? |
| Suppressions by reason | `onHedgeSuppressed` | "Never fires" versus "always blocked". |
| Quantiles of `ChainResult.elapsed()` | per kind of call | Did the tail move as computed? |

Re-measure after any change of model, reasoning setting, prompt size or price.

## 6. Worked example (synthetic numbers)

The latencies are lognormal, chosen for illustration:

| | Median | Tail |
|---|---|---|
| Stage 1 (primary) | 8 s | p95 30 s |
| Stage 2 (next) | 2 s | p99 6 s |

The price ratio is `r = 1.5`.

**Hedge rate and extra spend by delay** (`h(d) = 1 - F_1(d)`, `extra <= h(d) * r`):

| d | F_1(d) | hedge rate h(d) | extra spend (upper bound) |
|---|---|---|---|
| 5 s | 0.28 | 72% | 108% |
| 8 s (median) | 0.50 | 50% | 75% |
| 9 s | 0.56 | 44% | 66% |
| 15 s | 0.78 | 22% | 33% |
| 20 s | 0.87 | 13% | 19% |
| 25 s | 0.92 | 7.8% | 12% |
| 30 s (p95) | 0.95 | 5.0% | 7.5% |

**Background, budget B = 10%.** `F_1(d) >= 1 - 0.10 / 1.5 = 0.933`, so `d ~ 27 s`. A simulation of
`min(L_1, d + L_2)` gives:

| | p50 | p95 | p99 |
|---|---|---|---|
| without hedging | 8.0 s | 30.1 s | 51.9 s |
| `d = 27 s` | 8.0 s | 28.2 s | 30.0 s |

The p99 almost halves, for at most 10% more spend.

**Interactive, target p99 under 15 s.** `d <= 15 - q_2(0.99) = 15 - 6 = 9 s`. With `d = 9 s` the
simulated p95 is 12.2 s and the p99 13.6 s. The price is a 44% hedge rate and up to 66% extra spend
on interactive calls.

If interactive calls are a tenth of the volume, that adds about 6.6% to the total. Whether that is
worth halving the wait of someone watching a spinner is a product decision. The formulas only make
the price explicit.

**Resulting configuration:**

```java
HedgePolicy.builder()
        .backgroundDelay(Duration.ofSeconds(27))
        .interactiveDelay(Duration.ofSeconds(9))
        .maxCostRatio(2.0)
        .build();
```

With `stallAfter` at about 60 s (above the primary's p99 of 52 s) and a `callTimeout` of 90 s, a provider that hangs for everyone is skipped by new calls after the first stuck pair, while
the calls already stuck are rescued by their hedges.

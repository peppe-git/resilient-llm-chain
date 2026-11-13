# ADR-0005: The breaker also opens on stalled in-flight calls

- Status: accepted
- Date: v0.1

## Context

A consecutive-failure breaker learns from **completed** failures. When a provider hangs, the first
completed failure arrives only after the call timeout, so every call started in the meantime pays
the full timeout. A burst of parallel calls is the case that hurts most, and it is precisely the case
a completion-based breaker cannot see.

## Decision

- Every call is registered with the breaker while in flight (`StageBreaker.begin(...)` returns an
  `AutoCloseable` handle that must be closed in a `finally`).
- A stage is considered open when at least `stallThreshold` of its calls have been in flight for at
  least `stallAfter`. One slow call is slowness; several stuck together are a symptom.
- The stalled condition is **computed, not stored**. It ends by itself as soon as those calls end,
  with no cooldown to wait for, so a false positive is cheap: new calls move to the next stage for a
  while.
- An explicit cooldown (from failures) takes precedence over the stall in `snapshot()` status.

## Consequences

- Stall detection is per process; the shared verdict covers cooldowns only. Publishing stalls across
  processes would need a write per call start, which costs more than it saves.
- A stage whose calls are legitimately long (for example long-form generation with reasoning) needs a
  `stallAfter` above its normal p99. `Duration.ZERO` disables detection.

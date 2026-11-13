# ADR-0006: Share the breaker verdict across processes, not the counters

- Status: accepted
- Date: v0.1

## Context

With several processes calling the same providers, a breaker that lives only in memory has to learn
about an outage once per process: the cost of an outage becomes `threshold × processes` instead of
`threshold`.

## Decision

- Only the **verdict** is shared: "this stage is cooling down for another N ms". The verdict is the
  TTL of a key. Expiry is the mechanism, so no process has to remember to clean up.
- Counters stay local. Sharing them would cost an atomic write per failure for information that does
  not change the decision.
- The local breaker remains authoritative for its own observations. When the shared and local
  verdicts disagree, the **longer** cooldown wins.
- A success clears the shared verdict, so a recovered provider rejoins everywhere.
- A manual reset publishes a **reset marker** with its timestamp. A missing key means either
  "expired" or "reset", and only a reset must close a longer local cooldown opened before it. The
  marker lives as long as the longest possible cooldown.
- The shared state is read at most once per `sharedRefresh` interval per stage (default 5 s), never on
  every check.
- `SharedVerdictStore` implementations are best-effort by contract. A store that throws is caught and
  degraded to local-only behaviour anyway.

## Consequences

- Cooldown expiry is measured by the store (Redis), so process clocks do not matter for it. The reset
  marker does compare client timestamps; clock skew can misjudge a cooldown opened within the skew of
  a reset. This is acceptable for a manual operation.
- The in-memory store makes the same semantics available to several chains in one JVM and to tests.

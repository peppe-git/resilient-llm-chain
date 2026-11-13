# ADR-0003: An injectable clock with timers, and tests without sleeps

- Status: accepted
- Date: v0.1

## Context

Hedging, cooldowns, stall detection, call timeouts and retry backoff are all functions of time. The
original implementation waited with `poll(timeout)` and slept with `Thread.sleep`, and its tests
slept for hundreds of milliseconds. Those tests were slow and could flake on a loaded CI machine.

## Decision

- All time goes through `ChainClock`: `instant()`, `schedule(delay, action)` and `sleep(duration)`.
  `ChainClock.system()` is backed by `java.time.Clock` and a single daemon scheduler thread.
- The chain coordinator is an **event loop**. Stage completions, hedge timers and cancellation are
  all events on one queue. It never waits with a real-time timeout.
- `ManualChainClock` (public, in the core) moves time only when `advance(...)` is called, and runs
  due timers on the advancing thread, in due-time order. `sleep` advances the manual clock by the
  requested amount, so retry backoff is observable and instantaneous.
- Tests synchronise on latches and futures (for example "the fake provider has been entered"), never
  on elapsed wall time.

## Consequences

- The behaviour under test is the behaviour in production: the same coordinator, with a different
  clock.
- Users get a ready tool to test their own hedging and breaker configuration deterministically.
- The `sleep` of `ManualChainClock` advances the shared clock from whichever thread sleeps. Tests that
  combine retries with concurrent hedging must account for that; the library's own tests keep them
  separate.

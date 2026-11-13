# ADR-0001: Java 21 baseline, explicit call context instead of ScopedValue

- Status: accepted
- Date: v0.1

## Context

A chain runs each stage on its own thread so that a slow stage can be hedged and an overtaken stage
can be left to finish on its own. Overtaken stages are not interrupted: a blocking socket read does
not respond to interruption, and letting the call finish is what makes its spend observable. Those
threads therefore spend most of their life waiting on the network, and a hedged call can keep one
alive long after the caller has its answer.

Per-call information (is a person waiting? where do streamed tokens go? has the work been cancelled?
which labels identify the caller?) must reach the stage thread.

## Decision

- **Baseline Java 21.** The default stage executor is `Executors.newVirtualThreadPerTaskExecutor()`.
  Abandoned or overtaken stages cost a virtual thread, not a platform thread. The executor is
  injectable, so a caller can still bring their own.
- **No `ScopedValue`.** In Java 21 it is a preview API (JEP 446); it became final in Java 25
  (JEP 506). A library cannot require `--enable-preview` from its users.
- **No library-owned `ThreadLocal`s either.** Everything the chain needs travels in an explicit
  `CallOptions` value, and providers receive a `StageContext` parameter. For the caller's own
  thread-locals (MDC, tenant id, tracing) there is a `ContextPropagator` hook that wraps each stage
  task on the caller thread.

## Consequences

- Users on Java 17 cannot adopt v0.1.
- Signatures carry the context explicitly; there is no hidden state to forget to propagate.
- When the baseline moves to a release with final `ScopedValue`, the `ContextPropagator` hook can
  offer a ScopedValue-based implementation without changing the core API.

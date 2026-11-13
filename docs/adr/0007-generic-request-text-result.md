# ADR-0007: The library does not own the message model

- Status: accepted
- Date: v0.1

## Context

Message schemas (roles, multimodal parts, tool calls, reasoning controls) change with every vendor
release. A resilience library that defines its own schema either lags behind or grows into an SDK.

## Decision

- Providers are `LlmProvider<Q>`: the request type `Q` belongs to the user, and each provider adapts
  it to its vendor. A minimal `Prompt(system, user)` record is provided for the common case.
- The result is a fixed envelope, `LlmResult(text, model, usage)`, because output validation, usage
  accounting and pricing need a common denominator.
- Generation parameters (temperature, reasoning, effort) are not core concepts; they live inside `Q`.

## Consequences

- Structured outputs and tool calls are out of scope for v0.1: a provider can serialise them into
  `text`, but the chain does not interpret them.
- Every stage in a chain shares the same `Q`, so `Q` must be provider-neutral.

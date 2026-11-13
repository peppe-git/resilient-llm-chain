# ADR-0002: A dependency-free core, no Spring

- Status: accepted
- Date: v0.1

## Context

The code this library is derived from lived inside a Spring Boot application and classified errors by
catching Spring `RestClient` exception types. A resilience library that drags a web framework into
every user's classpath picks a side in the user's stack.

## Decision

- `resilient-llm-chain-core` has **no runtime dependencies**. Logging uses `java.lang.System.Logger`,
  which routes to whatever backend the application configures.
- Everything transport-specific goes behind interfaces the user implements or picks:
  `ErrorClassifier` (which exceptions are retryable, permanent, content-dependent; whether tokens were
  probably spent), `LlmProvider` (the call itself), `SharedVerdictStore` (cross-process state).
- Optional modules carry their own dependencies: `-redis` depends on Lettuce, the example adapter on
  Jackson. Spring integration is a v0.2 candidate and would be a separate starter.

## Consequences

- The core cannot know HTTP status codes by itself. `DefaultErrorClassifier` understands the
  library's own `ProviderHttpException` plus JDK network exceptions; other SDKs need a small
  classifier (typically 20 lines).
- Spring Boot 3.x and 4.x are both irrelevant to the core: it runs in either, and in neither.

# ADR-0008: LangChain4j and Spring AI adapters are deferred to v0.2

- Status: accepted
- Date: v0.1

## Context

A thin adapter (a framework `ChatModel` that delegates to a `ResilientChain`) would let framework users
adopt the chain without changing call sites.

## Decision

Not in v0.1. Both frameworks' chat-model APIs have changed shape repeatedly. Bridging streaming
(framework handlers versus `StageContext.emit`) is not trivial. Each adapter also adds a version
matrix that a 5-10 hours/week maintainer has to keep green. v0.1 ships the core, the optional Redis
store and one example HTTP adapter.

## Consequences

- Framework users wrap the chain themselves for now. The README shows the 15-line pattern.
- The adapters are the first v0.2 candidates, each as its own module with its own CI job.

# resilient-llm-chain

An in-process fallback chain for LLM providers on the JVM, with **hedging that knows about cost, load
and streaming**, and a **circuit breaker that sees stalled calls** before they fail.

> Status: **0.1.0-SNAPSHOT**. Not published to Maven Central yet. The API may change before 1.0.

## Why it exists

Calling two or more LLM providers with "try A, then B" goes wrong in four ways:

1. **A provider that hangs costs you the full timeout.** A serial chain only reaches B after A gives up.
   When A accepts the connection and then says nothing, every call pays the whole timeout before B is
   even tried.
2. **Naive hedging pays twice.** Starting B after a delay fixes the tail. But if your chain is ordered by
   quality, B is the expensive model, and hedging it against a cheap A that would have answered doubles
   the bill. Under load, extra calls add pressure exactly when there is the least headroom. And a stage
   that is already streaming its answer is not silent: it is writing a long answer.
3. **A failure-counting breaker cannot save the first burst.** It learns from completed failures. When a
   provider hangs, nothing completes until the timeouts expire, so every call started in the meantime
   pays in full.
4. **A per-process breaker relearns every outage.** With N processes, an outage costs
   `threshold × N` failed calls instead of `threshold`.

This library fixes those four things and stays out of the rest.

## What it does

- **Ordered chain with intra-provider fallback.** A spec like `"primary:model-a, backup"` is tried in
  order. An unpinned provider tries its default model, then its other models, then the next provider.
- **Cost-, load- and streaming-aware hedging.** When the running stage stays silent past a delay, the
  next stage starts too, and the first valid answer wins. The hedge is skipped when:
  - the candidate costs more than `maxCostRatio` × a stage in flight;
  - the concurrency limiter has no free permit;
  - the running stage has already streamed text.

  The delay can differ for interactive calls (someone is waiting) and background work.
- **Per-stage breaker with stall detection.** It opens after N consecutive failures, with a short
  cooldown for transient errors and a long one for "model not found / retired". A stage also counts as
  open while at least N calls have been in flight for more than X seconds, before any of them
  completes.
- **Shared verdict across processes.** The cooldown is published as the TTL of a Redis key (optional
  module). Only the verdict is shared, not the counters. A manual reset reaches every process, and an
  unreachable Redis degrades to the local breaker.
- **The rest of a careful chain:**
  - retries with jittered backoff, and `Retry-After` honoured exactly up to a cap;
  - a **total** time cap per call, on top of the socket read timeout;
  - output validation, where two consecutive rejections stop the chain;
  - fail-open when every stage is cooling down;
  - an estimate of the spend of abandoned calls;
  - cooperative cancellation;
  - usage events for every attempt, including discarded hedges.

Everything time-based goes through an injectable clock. The tests drive hedges, cooldowns, stalls,
timeouts and backoff with a manual clock, with no sleeps. The same `ManualChainClock` is public, so you
can test your own configuration the same way.

## Quickstart

Requires Java 21. Until the first release, build and install locally:

```bash
./mvnw install
```

```xml
<dependency>
    <groupId>io.github.peppe-git</groupId>
    <artifactId>resilient-llm-chain-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```java
LlmProvider<Prompt> primary = OpenAiCompatibleProvider.builder("primary", URI.create("https://api.example.com/v1"))
        .apiKey(() -> System.getenv("PRIMARY_KEY"))
        .defaultModel("small-model")
        .streaming(true)
        .build();
LlmProvider<Prompt> backup = OpenAiCompatibleProvider.builder("backup", URI.create("https://other.example.org/v1"))
        .apiKey(() -> System.getenv("BACKUP_KEY"))
        .defaultModel("other-model")
        .build();

ResilientChain<Prompt> chain = ResilientChain.<Prompt>builder()
        .provider(primary)
        .provider(backup)
        .hedging(HedgePolicy.builder()
                .backgroundDelay(Duration.ofSeconds(20))   // derive both from your own latencies:
                .interactiveDelay(Duration.ofSeconds(8))   // see docs/guides/hedge-tuning.md
                .maxCostRatio(2.0)
                .build())
        .priceTable(PriceTable.builder()
                .price("primary", "small-model", ModelPrice.perMillion(0.30, 1.20))
                .price("backup", "other-model", ModelPrice.perMillion(0.50, 2.00))
                .build())
        .callTimeout(Duration.ofSeconds(90))
        .concurrencyLimiter(ConcurrencyLimiter.of(16, Duration.ofSeconds(20)))
        .usageListener(event -> metrics.record(event))   // every attempt, discarded hedges included
        .build();

ChainResult answer = chain.execute(new Prompt("Be brief.", "Summarise this: ..."), CallOptions.interactive());
```

The prices above are placeholders. The library ships no prices: they are your data, in your currency.

Your own request type works too. Implement `LlmProvider<MyRequest>` for each vendor SDK and build a
`ResilientChain<MyRequest>`. To share the breaker verdict across processes, add
`resilient-llm-chain-redis` and pass `.sharedVerdictStore(new RedisSharedVerdictStore(connection))`.

### Inside a framework

There is no LangChain4j or Spring AI adapter in v0.1 (see [ADR-0008](docs/adr/0008-framework-adapters-deferred.md)).
The pattern is a few lines: implement the framework's chat-model interface and delegate to the chain.

```java
final class ChainBackedModel /* implements <your framework's chat model> */ {
    private final ResilientChain<Prompt> chain;

    ChainBackedModel(ResilientChain<Prompt> chain) {
        this.chain = chain;
    }

    String chat(String system, String user) {
        return chain.execute(new Prompt(system, user), CallOptions.interactive()).text();
    }
}
```

## Guarantees

- **One winner per call.** Later valid answers are reported as `DISCARDED`, with their usage.
- **Order is preserved.** A hedge is always the next stage of the spec. If the running stage answers
  first, it wins.
- **The breaker never fakes an outage.** If it skips every stage, the chain runs again ignoring it
  (fail-open). If a healthy stage was tried and failed, you get that real error.
- **Listeners cannot break a call.** Exceptions thrown by usage and event listeners are counted
  (`listenerFailures()`) and swallowed.
- **The core has no runtime dependencies** and no Spring.
- **Deterministic under a manual clock.** The coordinator never waits on wall-clock time; only the
  injected clock does.

## Non-guarantees

- **Overtaken or abandoned calls are not stopped.** A blocking socket read does not respond to
  interruption. Losers run until their provider answers or closes, on virtual threads, and their spend
  is reported. `callTimeout` frees the chain, not the socket.
- **Hedging costs money by design.** The cost cap bounds the price ratio, not the total spend. Measure
  with the usage events.
- **Stall detection is per process.** Only cooldowns are shared.
- **A cooldown adopted from the shared store is kept until it expires.** Only a manual reset
  (through its marker) clears it; a success elsewhere does not.
- **The reset marker compares process clocks.** Clock skew can misjudge a cooldown opened within the
  skew of a reset.
- **Text only.** Tool calls and structured outputs are not interpreted. A provider may serialise them
  into the text.
- **Not a gateway.** There are no virtual keys, budgets, quotas, caching or audit logs.

## Comparison

Checked against each project's documentation and source in November 2026. Corrections are welcome.

| | This library | Resilience4j 2.4 | Failsafe 3.3 | LangChain4j 1.23 | Spring AI 2.0 | LiteLLM Router | Portkey Gateway |
|---|---|---|---|---|---|---|---|
| Runs in-process on the JVM | yes | yes | yes | yes | yes | no (Python SDK or proxy) | no (gateway hop) |
| Ordered fallback across providers | yes | compose it yourself | `Fallback` policy | community `model-router` (`FailoverStrategy`) | no | yes | yes |
| Hedging | next stage, after a delay | `resilience4j-hedge`: duplicates the **same** call, fixed or average-based delay | no | no | no | no | no |
| Hedging aware of cost / load / first token | yes / yes / yes | no / no / no | — | — | — | — | — |
| Breaker per provider/model | yes | per instance you create | per instance you create | cooldown of a failed model (failover) | no | cooldowns per deployment | yes (per strategy path) |
| Breaker sees calls **in flight** (stalls) | yes | no: slow calls are classified when they complete | no | no | no | not documented: cooldowns count failures | not documented: counts responses by status |
| Shared state across processes | verdict via Redis TTL | no | no | no | no | cooldowns via Redis | not documented |
| First streamed chunk used as a liveness signal | yes: no hedge once it arrives | no | no | no | no | `stream_timeout` waits for the first chunk | the timeout stops once a chunk arrives |
| Provider SDKs included | one example adapter | — | — | many | many | hundreds | hundreds |

Sources:
- [Resilience4j hedge config](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-hedge/src/main/java/io/github/resilience4j/hedge/HedgeConfig.java),
  [circuit breaker metrics](https://github.com/resilience4j/resilience4j/blob/master/resilience4j-circuitbreaker/src/main/java/io/github/resilience4j/circuitbreaker/internal/CircuitBreakerMetrics.java),
  [distributed breaker request](https://github.com/resilience4j/resilience4j/issues/2298);
- [Failsafe policies](https://failsafe.dev/policies/), [hedging request](https://github.com/failsafe-lib/failsafe/issues/291);
- [gRPC request hedging](https://grpc.io/docs/guides/request-hedging/) (fixed delay, duplicate RPCs);
- [LangChain4j model router](https://docs.langchain4j.dev/integrations/model-router/),
  [model routing](https://docs.langchain4j.dev/tutorials/model-routing);
- [Spring AI retry](https://docs.spring.io/spring-ai/docs/current/api/org/springframework/ai/retry/RetryUtils.html);
- [LiteLLM routing](https://docs.litellm.ai/docs/routing), [reliability](https://docs.litellm.ai/docs/proxy/reliability),
  [timeouts](https://docs.litellm.ai/docs/proxy/timeout);
- [Portkey gateway](https://portkey.ai/docs/product/ai-gateway),
  [circuit breaker](https://portkey.ai/docs/product/ai-gateway/circuit-breaker),
  [request timeouts](https://portkey.ai/docs/product/ai-gateway/request-timeouts).

Where the others are better, say so:

- **LiteLLM and Portkey** are complete gateways: hundreds of providers, virtual keys, budgets, caching,
  dashboards, and shared cooldowns. If you need a central gateway for several teams or languages, use
  one of them.
- **Resilience4j** is the general-purpose toolbox for one dependency at a time. Its hedge is the right
  tool when the duplicate is the same call to an equivalent replica.
- **LangChain4j and Spring AI** give you the model abstractions. This library sits underneath them, and
  does not replace them.

## When not to use it

- You call a single provider: the SDK's retry is enough.
- You need a central gateway with keys, budgets and audit: use LiteLLM or Portkey.
- You are not on the JVM, or you cannot run Java 21.
- You need tool calls or structured outputs interpreted across the chain: v0.1 returns text.
- Your providers are interchangeable replicas of the same model: a plain hedge (Resilience4j, gRPC) is
  simpler.

## Modules

| Artifact | Contents | Dependencies |
|---|---|---|
| `resilient-llm-chain-core` | chain, breaker, hedging, retries, clock | none |
| `resilient-llm-chain-redis` | `RedisSharedVerdictStore` | Lettuce |
| `resilient-llm-chain-openai-compatible` | example adapter for `/chat/completions` | Jackson |

## Documentation

- [Concepts](docs/concepts.md): stage, chain, hedging, stall, shared verdict.
- [Configuration reference](docs/configuration.md).
- [Tuning the hedge delay](docs/guides/hedge-tuning.md): method and formulas.
- [Architecture decisions](docs/adr/).
- [Changelog](CHANGELOG.md), [contributing](CONTRIBUTING.md), [security](SECURITY.md).

## License

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

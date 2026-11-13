# Configuration reference

All settings are plain Java builders: there is no properties file in v0.1. A Spring Boot starter with
properties is a v0.2 candidate.

## `ResilientChain.Builder`

| Method | Default | Meaning |
|---|---|---|
| `provider(LlmProvider<Q>)` | (required, at least one) | Registers a provider. Names are case-insensitive, unique, and may not contain `,` or `:`. |
| `defaultSpec(ChainSpec)` | providers in registration order | Chain used by `execute(request)` and `execute(request, options)`. |
| `clock(ChainClock)` | `ChainClock.system()` | Time, timers and sleeps. Use `ManualChainClock` in tests. |
| `breaker(BreakerConfig)` | `BreakerConfig.defaults()` | Builds the breaker of this chain. |
| `breaker(StageBreaker)` | none | Uses an existing breaker, for example one shared by several chains. Overrides the config. |
| `sharedVerdictStore(SharedVerdictStore)` | none (local only) | Shares cooldowns across processes. Ignored when an existing breaker is given. |
| `hedging(HedgePolicy)` | `HedgePolicy.disabled()` | See below. |
| `priceTable(PriceTable)` | `PriceTable.none()` | Prices for cost-aware hedging and for the cost in usage events. |
| `retry(RetryPolicy)` | `RetryPolicy.defaults()` | Retries of `RETRYABLE` failures on the same stage. |
| `callTimeout(Duration)` | `ZERO` (none) | Total time cap of each provider call, beyond any socket timeout. |
| `callTimeout(String provider, Duration)` | none | Per-provider override. |
| `errorClassifier(ErrorClassifier)` | `DefaultErrorClassifier` | Maps failures to retry, breaker and spend decisions. |
| `outputValidator(OutputValidator)` | accept all | Rejects answers. Can be overridden per call. |
| `maxConsecutiveRejections(int)` | 2 | Rejections in a row after which the chain stops. |
| `usageListener(UsageListener)` | none | One event per stage attempt, whatever the outcome. |
| `eventListener(ChainEventListener)` | none | Skips, hedges started or suppressed, fail-open, stops, permanent failures. |
| `concurrencyLimiter(ConcurrencyLimiter)` | unbounded | One permit per chain call. Hedges need a free permit. |
| `abandonedSpendEstimator(AbandonedSpendEstimator<Q>)` | none (zero) | Token estimate for calls abandoned after generation started. |
| `contextPropagator(ContextPropagator)` | none | Carries thread-locals (MDC, tenant, tracing) to stage threads. |
| `stageExecutor(Executor)` | one virtual thread per stage | Overtaken stages keep running: size a bounded pool accordingly. |

## `CallOptions`

| Method | Default | Meaning |
|---|---|---|
| `interactive()` / `background()` | background | Selects the hedge delay and the limiter's wait policy. |
| `withCancellation(CancellationToken)` | `NONE` | Cooperative cancellation. |
| `withStream(StreamListener)` | none | Streamed preview. |
| `withLabel(k, v)` / `withLabels(map)` | empty | Attribution passed to providers (`StageContext.labels()`) and usage events. |
| `withValidator(OutputValidator)` | the chain's | Per-call validator. |

## `HedgePolicy`

| Setting | Default | Meaning |
|---|---|---|
| `backgroundDelay` | `ZERO` (disabled) | Silence after which the next stage starts, for background calls. Zero disables hedging altogether. |
| `interactiveDelay` | = background | Same for interactive calls. Clamped to the background delay; zero disables hedging for interactive calls only. |
| `maxCostRatio` | 2.0 | A stage may start next to another only if its price basis is at most this many times the other's. `<= 0` disables the rule. |
| `costBasket(in, out)` | 1,000,000 / 1,000,000 | Tokens the price basis is computed on. |

Shortcuts: `HedgePolicy.after(delay)` uses one delay for both kinds of call, and
`HedgePolicy.disabled()` turns hedging off.

## `BreakerConfig`

| Setting | Default | Meaning |
|---|---|---|
| `enabled` | true | A disabled breaker never opens and reports nothing. |
| `failureThreshold` | 3 | Consecutive failures (after retries) that open the cooldown. |
| `cooldown` | 60 s | Cooldown after transient failures. |
| `permanentCooldown` | 1 h | Cooldown after a permanent failure (model not found or retired), opened at once. |
| `stallAfter` | 60 s | Age after which a call in flight counts as stalled. `ZERO` disables stall detection. |
| `stallThreshold` | 2 | Stalled calls that make the stage count as open. |
| `sharedRefresh` | 5 s | How often a stage's shared verdict is read. |

These defaults are generic starting points, not measurements. Rules of thumb:
- `stallAfter` should sit above the normal p99 of the stage's successful calls and below
  `callTimeout`;
- `cooldown` should be long enough to skip a few calls, and short enough that a recovered provider
  comes back quickly.

## `RetryPolicy`

| Setting | Default | Meaning |
|---|---|---|
| `maxAttempts` | 3 | Calls per stage, the first included. `RetryPolicy.none()` = 1. |
| `baseDelay` | 500 ms | First backoff, doubled at each retry. |
| `maxDelay` | 5 s | Cap on the computed backoff, applied after jitter. |
| `maxRetryAfter` | 5 s | Cap on a `Retry-After` the provider asks for. Below the cap it is honoured exactly, without jitter. |
| `jitterSpread` | 0.5 | The backoff is multiplied by a factor in `[1 - spread, 1 + spread)`. |
| `random` | `ThreadLocalRandom` | Inject a constant for deterministic tests. |

## `ConcurrencyLimiter`

`ConcurrencyLimiter.of(permits, interactiveWait)`:

- **Fair.** The semaphore is fair.
- **Background calls** wait for a permit without limit.
- **Interactive calls** wait at most `interactiveWait`, then throw `ChainSaturatedException`.

`totalWait()` and `waits()` give the mean wait.

## `DefaultErrorClassifier`

| Failure | Kind | Tokens probably spent |
|---|---|---|
| `ProviderHttpException` 404, 410 | `PERMANENT` | no |
| `ProviderHttpException` 429, 5xx | `RETRYABLE` (with `Retry-After`) | no |
| `ProviderHttpException`, other status | `FAILURE` | no |
| `ContentRefusedException` | `CONTENT` | as declared |
| connect failure, unknown host, connect timeout | `FAILURE` | no |
| `CallTimeoutException`, read timeout, anything else | `FAILURE` | yes |

When you use a vendor SDK, write a classifier for its exceptions. It is usually a dozen lines that map
the SDK's status-carrying exception to these kinds.

## `RedisSharedVerdictStore`

`new RedisSharedVerdictStore(connection, prefix, commandTimeout)`:

| Parameter | Default | Meaning |
|---|---|---|
| `connection` | (required) | A Lettuce `StatefulRedisConnection<String, String>`. It is never closed by the store. |
| `prefix` | `rlc:breaker:` | Key namespace. Give each environment its own. |
| `commandTimeout` | 250 ms | Longest wait for one command. On timeout or error the store answers "nothing known". |

Keys:

| Key | Content | TTL |
|---|---|---|
| `<prefix><provider>\|<model>` | `1` | the cooldown |
| `<prefix>reset:<provider>\|<model>` | the reset time in epoch milliseconds | the longest cooldown |

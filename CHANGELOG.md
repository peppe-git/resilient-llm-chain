# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). Before 1.0, minor versions may break the API.

## [Unreleased]

### Added

- `resilient-llm-chain-core` (no runtime dependencies):
  - `ResilientChain<Q>` with chain specs `provider[:model], ...` and intra-provider model fallback;
  - hedging that is aware of cost (`maxCostRatio` on a price basket), load (concurrency limiter) and
    streaming (no hedge once the running stage has streamed), with separate interactive and background
    delays;
  - `StageBreaker`: per-stage consecutive-failure breaker, short and permanent cooldowns, stall
    detection on calls in flight, shared verdict through `SharedVerdictStore`, manual reset marker;
  - retries with jittered exponential backoff and an exact, capped `Retry-After` (delay-seconds and
    HTTP-date);
  - a total call timeout on top of socket timeouts;
  - output validation that stops the chain after consecutive rejections;
  - fail-open when every stage is cooling down;
  - abandoned-spend estimation, cooperative cancellation, streaming ownership;
  - usage and chain events, and a context propagation hook;
  - `ChainClock` and `ManualChainClock`, for deterministic tests without sleeps.
- `resilient-llm-chain-redis`: `RedisSharedVerdictStore` on Lettuce, with a timeout on every command.
- `resilient-llm-chain-openai-compatible`: an example adapter for `/chat/completions`, with
  streaming.
- Documentation: concepts, configuration reference, hedge tuning guide, architecture decision records.
- `scripts/scrub-check.sh` and `scripts/mutation-check.sh`.
- Release build (`-Prelease`: sources, javadoc, signatures, Central Publisher Portal upload without
  automatic publishing), `release` workflow on `v*` tags, Dependabot configuration and `RELEASING.md`.

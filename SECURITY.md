# Security policy

## Supported versions

Only the latest 0.x release receives fixes. There is no release yet: the project is at
0.1.0-SNAPSHOT.

## Reporting a vulnerability

Please do **not** open a public issue. Use GitHub's private vulnerability reporting on this repository
("Security" tab, "Report a vulnerability"). You should get a first answer within a week. Coordinated
disclosure is the goal: a fix is released before the details are published.

## What to keep in mind when using the library

- **API keys** live in your adapters. The example adapter reads its key from a `Supplier` at every
  call and never logs it.
- **Error bodies.** `ProviderHttpException` keeps a one-line excerpt (200 characters) of the provider's
  error body, and the breaker's snapshot keeps the last error description. Providers sometimes echo
  parts of the request in error bodies. If your prompts contain sensitive data, treat breaker
  snapshots and logs accordingly, or write a classifier and adapter that drop the body.
- **Shared verdict keys** contain provider and model names only, never request content. Protect the
  Redis instance like any other shared infrastructure: anyone who can write those keys can make
  stages be skipped.
- **Overtaken calls keep running** until their provider answers, by design. Size thread pools,
  connection pools and provider rate limits with hedging in mind.

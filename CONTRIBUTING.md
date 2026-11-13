# Contributing

Thank you for considering a contribution. The project is maintained in spare time, so small, focused
changes with tests are the ones most likely to be merged quickly.

## Scope

The library is deliberately narrow: chain execution, hedging, the per-stage breaker and the shared
verdict.

**Out of scope:**
- vendor SDK adapters (they age in months; write yours on top of `LlmProvider`);
- prompt management;
- quotas, budgets, keys and gateways.

Please open an issue to discuss anything larger than a bug fix before writing code.

## Building

Java 21 is required. The Maven wrapper is included.

```bash
./mvnw verify
```

The Redis module's tests use Testcontainers and are skipped when Docker is not available.

## Tests

- **No sleeps.** Everything time-based goes through `ChainClock`.
  - Use `ManualChainClock` and advance it.
  - Wait for "the provider was entered" with latches or futures.
  - Use `ManualChainClock.awaitTimers` before advancing.
- **Real-time waits only as safety nets** (a few seconds) that fail a broken test instead of hanging
  it.
- **Every guard must be seen red.** Add a mutation to `scripts/mutation-check.sh` that removes the
  behaviour, and check that your test catches it:

  ```bash
  scripts/mutation-check.sh <id>
  ```

- **Fake providers only.** Never a real vendor, a real model name as a default, or a measured price.

## Hygiene

`scripts/scrub-check.sh` runs in CI and before every commit. It has two sets of rules.

**Generic rules, always on.** They fail on:
- issue ids, calendar dates, money amounts;
- IP addresses, e-mail addresses, secrets, UUIDs;
- committed environment files;
- untranslated comments in code and build files.

**Private rules, from a file outside the repository.** Names that must never appear here are not
written in the repository at all, not even inside the script. The maintainer keeps them in a pattern
file and points the script to it with an environment variable:

```bash
SCRUB_PRIVATE_PATTERNS="$HOME/.config/scrub/private-patterns.txt" ./scripts/scrub-check.sh
```

The file holds one extended regular expression per line, matched case-insensitively. Empty lines and
lines starting with `#` are ignored:

```
# names that must not be published
acme[^a-z0-9]{0,3}widgets
\binternal-codename\b
```

| Situation | Behaviour |
|---|---|
| Variable not set | Warning on stderr; generic rules only. This is what CI and contributors run. |
| Variable set, file missing | Exit code 2. |
| Pattern that is not a valid regular expression | Exit code 2, with the line number. |

The private rules apply to every file, the script included. Never copy the pattern file into the
repository.

Every source file starts with `SPDX-License-Identifier: Apache-2.0`.

## Commits and pull requests

- Commit messages follow `type(scope): sentence`, in English, for example
  `fix(breaker): keep the longer cooldown when adopting a shared verdict`.
- One logical change per pull request, with tests and documentation updated together.
- Contributions are licensed under the Apache License 2.0, like the project.

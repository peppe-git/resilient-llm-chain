#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Sees every guard red: applies one deliberate defect at a time to the core, runs the tests that should
# catch it, and restores the file. A mutation that leaves its tests green is reported as SURVIVED and
# makes the script fail. Run it from a clean working tree; it restores files with `git checkout`.
#
# Usage: scripts/mutation-check.sh            (all mutations)
#        scripts/mutation-check.sh 3 7        (only mutations 3 and 7)
set -uo pipefail

cd "$(git rev-parse --show-toplevel)"
if ! git diff --quiet -- core/src/main redis/src/main openai-compatible/src/main; then
    echo "main sources have uncommitted changes: commit or stash them first" >&2
    exit 2
fi

SRC=core/src/main/java/io/github/peppe_git/resilientllmchain
RUN=$SRC/internal/ChainRun.java
BRK=$SRC/breaker/StageBreaker.java
REDIS=redis/src/main/java/io/github/peppe_git/resilientllmchain/redis/RedisSharedVerdictStore.java
OPENAI=openai-compatible/src/main/java/io/github/peppe_git/resilientllmchain/openai/OpenAiCompatibleProvider.java

# id ~ module ~ file ~ perl substitution ~ tests expected to fail ~ what the defect does
MUTATIONS=(
  "1~core~$BRK~s/return stalledCalls\(key, now\) >= config.stallThreshold\(\);/return false;/~StageBreakerStallTest,ChainBreakerTest~stall detection ignored by isOpen"
  "2~core~$RUN~s/if \(!inFlight.isEmpty\(\) && !affordable\(candidate\)\)/if (false)/~ChainHedgeTest~hedge cost cap removed"
  "3~core~$RUN~s/if \(stream != null && stream.started\(\)\) \{\n                        notifyEvent/if (false) {\n                        notifyEvent/~ChainHedgeTest~hedge ignores a stream that already started"
  "4~core~$RUN~s/if \(settings.limiter\(\).availablePermits\(\) <= 0\)/if (false)/~ChainHedgeTest~hedge ignores a saturated limiter"
  "5~core~$RUN~s/hedgeTimer = clock.schedule\(delay, \(\) -> events.offer\(new HedgeDue\(seq\)\)\);/hedgeTimer = null;/~ChainHedgeTest~hedge timer never armed"
  "6~core~$RUN~s/settings.hedge\(\).delay\(options.isInteractive\(\)\)/settings.hedge().delay(false)/~ChainHedgeTest~interactive delay ignored"
  "7~core~$RUN~s/if \(!pass.attempted\(\) && pass.skippedByBreaker\(\)/if (false \&\& pass.skippedByBreaker()/~ChainBreakerTest~fail-open removed"
  "8~core~$RUN~s/st.rejections >= settings.maxConsecutiveRejections\(\)/st.rejections >= 99/~ChainRejectionTest~chain never stops on rejections"
  "9~core~$RUN~s/                        st.rejections = 0;\n//~ChainRejectionTest~a failure no longer resets the rejection count"
  "10~core~$SRC/error/DefaultErrorClassifier.java~s/status == 404 \|\| status == 410/status == 999/~DefaultErrorClassifierTest,ChainBreakerTest,CallTimeoutTest~404 and 410 no longer permanent"
  "11~core~$SRC/retry/RetryPolicy.java~s/if \(retryAfter != null\) \{/if (false) {/~RetryPolicyTest,ChainRetryTest~Retry-After ignored"
  "12~core~$RUN~s/if \(timeout == null \|\| timeout.isZero\(\)\) \{/if (true) {/~CallTimeoutTest~total call timeout removed"
  "13~core~$BRK~s/        if \(shared == null\) \{\n            return;\n        \}\n        Long last/        if (true) {\n            return;\n        }\n        Long last/~StageBreakerSharedTest~shared verdict never adopted"
  "14~core~$BRK~s/if \(resetAt > 0 && seenOpen > 0 && seenOpen <= resetAt\)/if (false)/~StageBreakerSharedTest~reset marker ignored"
  "15~core~$BRK~s/        s.openUntil = 0L;\n        s.adoptedAt = 0L;/        s.adoptedAt = 0L;/~StageBreakerTest~a success no longer closes the cooldown"
  "16~core~$RUN~s/if \(!settled.compareAndSet\(false, true\)\)/if (false)/~ChainHedgeTest,ChainCancellationTest~no single winner"
  "17~core~$SRC/internal/StreamChannel.java~s/        if \(owner != stage\) \{\n            return;\n        \}\n        emitted = true;/        emitted = true;/~StreamChannelTest~stream owned by every writer"
  "18~core~$RUN~s/classification.generationStarted\(\) \? estimate\(ref, error\) : Usage.ZERO/Usage.ZERO/~CallTimeoutTest~abandoned spend never estimated"
  "19~core~$RUN~s/\} else if \(classification.kind\(\) != ErrorKind.CONTENT\) \{/} else {/~ChainBreakerTest~content refusals count against the breaker"
  "20~core~$RUN~s/options.cancellation\(\).onCancel\(\(\) -> events.offer\(Cancelled.INSTANCE\)\)/options.cancellation().onCancel(() -> { })/~ChainCancellationTest~cancellation of a call in flight ignored"
  "21~core~$BRK~s/        for \(long startedAt : calls.values\(\)\) \{\n            if \(now - startedAt >= stallMillis\) \{/        for (long startedAt : calls.values()) {\n            if (true) {/~StageBreakerStallTest~every call in flight counts as stalled"
  "22~core~$RUN~s/if \(honorBreaker && breaker.isOpen\(candidate.ref\(\)\)\)/if (false)/~ChainBreakerTest~open breakers no longer skip stages"
  "23~redis~$REDIS~s/SetArgs.Builder.px\(cooldown.toMillis\(\)\)/new SetArgs()/~RedisSharedVerdictStoreTest~open verdict written without a TTL"
  "24~redis~$REDIS~s/return ttl == null \|\| ttl <= 0 \? Duration.ZERO : Duration.ofMillis\(ttl\);/return Duration.ZERO;/~RedisSharedVerdictStoreTest~shared verdict never read back"
  "25~redis~$REDIS~s/return future.get\(commandTimeout.toNanos\(\), TimeUnit.NANOSECONDS\);/return future.get();/~RedisSharedVerdictStoreTest~no per-command timeout"
  "26~openai-compatible~$OPENAI~s/response.headers\(\).firstValue\(\"Retry-After\"\).orElse\(null\), text\)/null, text)/~OpenAiCompatibleProviderTest~Retry-After header dropped"
  "27~openai-compatible~$OPENAI~s/                    context.emit\(piece\);\n//~OpenAiCompatibleProviderTest~streamed pieces not forwarded"
  "28~openai-compatible~$OPENAI~s/if \(\"content_filter\".equals/if (\"never\".equals/~OpenAiCompatibleProviderTest~content filter not reported as a refusal"
)

selected=("$@")
survived=0
for entry in "${MUTATIONS[@]}"; do
    IFS='~' read -r id module file subst tests what <<<"$entry"
    if [ ${#selected[@]} -gt 0 ] && [[ ! " ${selected[*]} " =~ " $id " ]]; then
        continue
    fi
    if ! git ls-files --error-unmatch "$file" >/dev/null 2>&1; then
        echo "mutation $id SKIPPED: $file is not committed, so it could not be restored" >&2
        survived=$((survived + 1))
        continue
    fi
    before=$(shasum "$file" | cut -d' ' -f1)
    perl -0pi -e "$subst" "$file"
    after=$(shasum "$file" | cut -d' ' -f1)
    if [ "$before" = "$after" ]; then
        echo "mutation $id NOT APPLIED ($what): the pattern no longer matches $file"
        survived=$((survived + 1))
        continue
    fi
    ./mvnw -q -o -pl "$module" -am test -Dtest="$tests" -Dsurefire.failIfNoSpecifiedTests=false \
        -Dsurefire.timeout=180 >target-mutation.log 2>&1
    status=$?
    git checkout -q -- "$file"
    if grep -q 'COMPILATION ERROR' target-mutation.log; then
        echo "mutation $id INVALID ($what): it does not compile"
        survived=$((survived + 1))
    elif [ $status -eq 0 ]; then
        echo "mutation $id SURVIVED ($what): $tests stayed green"
        survived=$((survived + 1))
    else
        failed=$(grep -E 'Tests run: .*Fail' target-mutation.log | tail -1 | sed 's/^\[[A-Z]*\] //')
        echo "mutation $id killed ($what): $failed"
    fi
    rm -f target-mutation.log
done

if [ $survived -gt 0 ]; then
    echo "mutation-check: $survived mutation(s) survived" >&2
    exit 1
fi
echo "mutation-check: every mutation was caught" >&2

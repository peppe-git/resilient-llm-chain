// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.breaker;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.peppe_git.resilientllmchain.StageRef;
import io.github.peppe_git.resilientllmchain.time.ManualChainClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Two breakers sharing one store stand for two processes. */
class StageBreakerSharedTest {

    private static final StageRef STAGE = new StageRef("alpha", "model-a");
    private static final Duration COOLDOWN = Duration.ofSeconds(90);
    private static final Duration REFRESH = Duration.ofSeconds(5);

    private final ManualChainClock clock = new ManualChainClock();
    private final InMemorySharedVerdictStore store = new InMemorySharedVerdictStore(clock);
    private final BreakerConfig config = BreakerConfig.builder()
            .failureThreshold(2)
            .cooldown(COOLDOWN)
            .permanentCooldown(Duration.ofHours(1))
            .sharedRefresh(REFRESH)
            .build();
    private final StageBreaker api = new StageBreaker(config, clock, store);
    private final StageBreaker worker = new StageBreaker(config, clock, store);

    private void openOn(StageBreaker breaker) {
        breaker.recordFailure(STAGE, "e");
        breaker.recordFailure(STAGE, "e");
    }

    @Test
    void openingTheCooldown_publishesIt() {
        openOn(api);

        assertThat(store.remaining(STAGE.key())).isEqualTo(COOLDOWN);
    }

    @Test
    void aStageOpenElsewhere_isSkippedHereToo() {
        openOn(api);

        assertThat(worker.isOpen(STAGE)).isTrue();
        assertThat(worker.snapshot().getFirst().lastOpenedAt()).as("adopted, not opened here").isNull();
    }

    @Test
    void nothingOpenElsewhere_nothingSkipped_andNoLocalTrace() {
        assertThat(worker.isOpen(STAGE)).isFalse();
        assertThat(worker.snapshot()).isEmpty();
    }

    @Test
    void theSharedVerdict_isNotReadOnEveryCheck() {
        CountingStore counting = new CountingStore(store);
        StageBreaker breaker = new StageBreaker(config, clock, counting);

        breaker.isOpen(STAGE);
        breaker.isOpen(STAGE);
        breaker.isOpen(STAGE);
        assertThat(counting.reads.get()).isEqualTo(1);

        clock.advance(REFRESH);
        breaker.isOpen(STAGE);
        assertThat(counting.reads.get()).isEqualTo(2);
    }

    @Test
    void aSuccess_clearsTheSharedVerdict_forProcessesThatHaveNotAdoptedItYet() {
        openOn(api);
        StageBreaker latecomer = new StageBreaker(config, clock, store);

        api.recordSuccess(STAGE);

        assertThat(store.remaining(STAGE.key())).isZero();
        assertThat(latecomer.isOpen(STAGE)).isFalse();
    }

    @Test
    void aProcessThatAdoptedTheCooldown_keepsItUntilItExpires_unlessReset() {
        // Documented limit: a success elsewhere clears the store, not the copies already adopted. Only a
        // manual reset reaches those, through its marker.
        openOn(api);
        assertThat(worker.isOpen(STAGE)).isTrue();

        api.recordSuccess(STAGE);
        clock.advance(REFRESH);
        assertThat(worker.isOpen(STAGE)).isTrue();

        clock.advance(COOLDOWN);
        assertThat(worker.isOpen(STAGE)).isFalse();
    }

    @Test
    void aHealthySuccess_doesNotTouchTheStore() {
        CountingStore counting = new CountingStore(store);
        StageBreaker breaker = new StageBreaker(config, clock, counting);

        breaker.recordSuccess(STAGE);
        breaker.recordSuccess(STAGE);

        assertThat(counting.clears.get()).isZero();
    }

    @Test
    void aManualReset_appliesToTheWholeCluster() {
        openOn(api);
        assertThat(worker.isOpen(STAGE)).isTrue();

        clock.advance(Duration.ofSeconds(1));
        api.reset(STAGE);
        clock.advance(REFRESH);

        assertThat(api.isOpen(STAGE)).isFalse();
        assertThat(worker.isOpen(STAGE)).isFalse();
    }

    @Test
    void aResetElsewhere_closesTheCooldownThisProcessOpenedItself() {
        // The worker opens the cooldown on its own (its failures) and the store is then cleared by a
        // reset elsewhere: a missing key alone would leave the worker's local cooldown open.
        openOn(worker);
        clock.advance(Duration.ofSeconds(1));

        api.reset(STAGE);
        clock.advance(REFRESH);

        assertThat(worker.isOpen(STAGE)).isFalse();
    }

    @Test
    void aReopeningAfterTheReset_isNotClosedByTheMarker() {
        openOn(worker);
        clock.advance(Duration.ofSeconds(1));
        api.reset(STAGE);
        clock.advance(REFRESH);
        assertThat(worker.isOpen(STAGE)).isFalse();

        clock.advance(Duration.ofSeconds(1));
        openOn(worker);
        clock.advance(REFRESH);

        assertThat(worker.isOpen(STAGE)).isTrue();
    }

    @Test
    void aLongerLocalCooldown_isNeverShortenedByTheSharedOne() {
        worker.recordPermanentFailure(STAGE, "gone");
        store.clear(STAGE.key());
        store.publishOpen(STAGE.key(), Duration.ofSeconds(1));
        clock.advance(REFRESH);

        assertThat(worker.isOpen(STAGE)).isTrue();
        clock.advance(Duration.ofMinutes(30));
        assertThat(worker.isOpen(STAGE)).isTrue();
    }

    @Test
    void naturalExpiryElsewhere_doesNotShortenTheLocalCooldown() {
        worker.recordPermanentFailure(STAGE, "gone");
        store.clear(STAGE.key());
        clock.advance(REFRESH);

        assertThat(worker.isOpen(STAGE)).isTrue();
    }

    @Test
    void aFailingStore_degradesToTheLocalBreaker() {
        StageBreaker breaker = new StageBreaker(config, clock, new BrokenStore());

        assertThat(breaker.isOpen(STAGE)).isFalse();
        breaker.recordFailure(STAGE, "e");
        breaker.recordFailure(STAGE, "e");
        assertThat(breaker.isOpen(STAGE)).isTrue();
        breaker.recordSuccess(STAGE);
        assertThat(breaker.isOpen(STAGE)).isFalse();
        breaker.recordPermanentFailure(STAGE, "gone");
        assertThat(breaker.reset(STAGE)).isTrue();
    }

    @Test
    void withoutAStore_theBreakerIsLocal() {
        StageBreaker local = StageBreaker.local(config, clock);
        openOn(api);

        assertThat(local.isOpen(STAGE)).isFalse();
    }

    private static final class CountingStore implements SharedVerdictStore {
        final SharedVerdictStore delegate;
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger clears = new AtomicInteger();

        CountingStore(SharedVerdictStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void publishOpen(String key, Duration cooldown) {
            delegate.publishOpen(key, cooldown);
        }

        @Override
        public Duration remaining(String key) {
            reads.incrementAndGet();
            return delegate.remaining(key);
        }

        @Override
        public boolean clear(String key) {
            clears.incrementAndGet();
            return delegate.clear(key);
        }

        @Override
        public boolean publishReset(String key, Instant at, Duration ttl) {
            return delegate.publishReset(key, at, ttl);
        }

        @Override
        public Optional<Instant> lastReset(String key) {
            return delegate.lastReset(key);
        }
    }

    private static final class BrokenStore implements SharedVerdictStore {
        @Override
        public void publishOpen(String key, Duration cooldown) {
            throw new IllegalStateException("store down");
        }

        @Override
        public Duration remaining(String key) {
            throw new IllegalStateException("store down");
        }

        @Override
        public boolean clear(String key) {
            throw new IllegalStateException("store down");
        }

        @Override
        public boolean publishReset(String key, Instant at, Duration ttl) {
            throw new IllegalStateException("store down");
        }

        @Override
        public Optional<Instant> lastReset(String key) {
            throw new IllegalStateException("store down");
        }
    }
}

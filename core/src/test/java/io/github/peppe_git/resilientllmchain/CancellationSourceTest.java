// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CancellationSourceTest {

    @Test
    void cancel_runsRegisteredActionsOnce() {
        CancellationSource source = new CancellationSource();
        AtomicInteger runs = new AtomicInteger();
        source.onCancel(runs::incrementAndGet);

        source.cancel();
        source.cancel();

        assertThat(source.isCancelled()).isTrue();
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void registeringAfterCancellation_runsAtOnce() {
        CancellationSource source = new CancellationSource();
        source.cancel();
        AtomicInteger runs = new AtomicInteger();

        source.onCancel(runs::incrementAndGet);

        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void aClosedRegistration_doesNotRun() {
        CancellationSource source = new CancellationSource();
        AtomicInteger runs = new AtomicInteger();
        source.onCancel(runs::incrementAndGet).close();

        source.cancel();

        assertThat(runs.get()).isZero();
    }

    @Test
    void theNoneToken_isNeverCancelled() {
        AtomicInteger runs = new AtomicInteger();
        CancellationToken.NONE.onCancel(runs::incrementAndGet).close();

        assertThat(CancellationToken.NONE.isCancelled()).isFalse();
        assertThat(runs.get()).isZero();
    }
}

// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.peppe_git.resilientllmchain.StreamListener;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class StreamChannelTest {

    private final List<String> received = new CopyOnWriteArrayList<>();
    private final AtomicLong failures = new AtomicLong();
    private final StreamChannel channel = new StreamChannel(new StreamListener() {
        @Override
        public void delta(String text) {
            received.add(text);
        }

        @Override
        public void reset() {
            received.add("<reset>");
        }
    }, failures);
    private final Object stageA = new Object();
    private final Object stageB = new Object();

    @Test
    void theFirstStageToWrite_ownsThePreview_othersAreIgnored() {
        channel.delta(stageA, "a1");
        channel.delta(stageB, "b1");
        channel.delta(stageA, "a2");

        assertThat(received).containsExactly("a1", "a2");
        assertThat(channel.started()).isTrue();
    }

    @Test
    void theOwnerGivingUp_resetsThePreview_andFreesTheSeat() {
        channel.delta(stageA, "a1");
        channel.release(stageA);
        channel.delta(stageB, "b1");

        assertThat(received).containsExactly("a1", "<reset>", "b1");
    }

    @Test
    void aStageThatNeverWrote_releasesWithoutAReset() {
        channel.delta(stageA, "a1");
        channel.release(stageB);
        channel.attemptStarted(stageB);

        assertThat(received).containsExactly("a1");
    }

    @Test
    void afterClosing_lateWritersNoLongerReachTheListener() {
        channel.delta(stageA, "a1");
        channel.close();
        channel.delta(stageA, "late");
        channel.release(stageA);

        assertThat(received).containsExactly("a1");
    }

    @Test
    void emptyPieces_areIgnored_andDoNotCountAsStarted() {
        channel.delta(stageA, "");
        channel.delta(stageA, null);

        assertThat(received).isEmpty();
        assertThat(channel.started()).isFalse();
    }

    @Test
    void aThrowingListener_isCounted_notPropagated() {
        StreamChannel broken = new StreamChannel(new StreamListener() {
            @Override
            public void delta(String text) {
                throw new IllegalStateException("socket closed");
            }

            @Override
            public void reset() {
                throw new IllegalStateException("socket closed");
            }
        }, failures);

        broken.delta(stageA, "x");
        broken.release(stageA);

        assertThat(failures.get()).isEqualTo(2);
    }
}

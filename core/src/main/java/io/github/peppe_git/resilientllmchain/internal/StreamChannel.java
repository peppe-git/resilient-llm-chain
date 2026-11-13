// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.internal;

import io.github.peppe_git.resilientllmchain.StreamListener;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The streaming channel of one chain call. With hedging two stages can write at once: the first stage
 * that emits owns the preview and the others are dropped. If the owner fails or restarts its generation
 * (a retry), the listener gets {@code reset()} and the seat is free again. After the chain call returns
 * the channel is closed and late writers no longer reach the listener. All operations are serialised.
 */
final class StreamChannel {

    private final StreamListener listener;
    private final AtomicLong listenerFailures;
    private Object owner;
    private boolean emitted;
    private boolean closed;
    private volatile boolean started;

    StreamChannel(StreamListener listener, AtomicLong listenerFailures) {
        this.listener = Objects.requireNonNull(listener, "listener");
        this.listenerFailures = listenerFailures;
    }

    /** True once any text reached the listener. A stage that writes is not silent: no hedge. */
    boolean started() {
        return started;
    }

    synchronized void delta(Object stage, String text) {
        if (closed || text == null || text.isEmpty()) {
            return;
        }
        if (owner == null) {
            owner = stage;
        }
        if (owner != stage) {
            return;
        }
        emitted = true;
        started = true;
        try {
            listener.delta(text);
        } catch (RuntimeException e) {
            listenerFailures.incrementAndGet();
        }
    }

    /** {@code stage} starts (again) generating: if it owned the preview and wrote, the preview restarts. */
    synchronized void attemptStarted(Object stage) {
        if (closed || owner != stage || !emitted) {
            return;
        }
        emitted = false;
        reset();
    }

    /** {@code stage} gives up (failed or overtaken): if it owned the preview, the seat is freed. */
    synchronized void release(Object stage) {
        if (closed || owner != stage) {
            return;
        }
        owner = null;
        if (emitted) {
            emitted = false;
            reset();
        }
    }

    synchronized void close() {
        closed = true;
    }

    private void reset() {
        try {
            listener.reset();
        } catch (RuntimeException e) {
            listenerFailures.incrementAndGet();
        }
    }
}

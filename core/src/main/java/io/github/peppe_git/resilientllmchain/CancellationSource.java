// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** A {@link CancellationToken} cancelled by calling {@link #cancel()}. Thread-safe. */
public final class CancellationSource implements CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CopyOnWriteArrayList<Runnable> actions = new CopyOnWriteArrayList<>();

    /** Cancels the token and runs the registered actions. Idempotent. */
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            for (Runnable action : actions) {
                action.run();
            }
            actions.clear();
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public Registration onCancel(Runnable action) {
        Objects.requireNonNull(action, "action");
        actions.add(action);
        // Re-check after adding: a concurrent cancel() may have run before the action was visible.
        if (cancelled.get() && actions.remove(action)) {
            action.run();
        }
        return () -> actions.remove(action);
    }
}

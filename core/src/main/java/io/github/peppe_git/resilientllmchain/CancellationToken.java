// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

/**
 * Cooperative cancellation of a chain call.
 *
 * <p>After cancellation no new stage starts and no retry is attempted, and the chain call returns
 * promptly with {@link ChainCancelledException}. Calls already in flight are <b>not</b> interrupted:
 * they finish on their own and their spend is still reported to the {@code UsageListener}.
 */
public interface CancellationToken {

    /** A token that is never cancelled. */
    CancellationToken NONE = new CancellationToken() {
        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public Registration onCancel(Runnable action) {
            return () -> { };
        }
    };

    boolean isCancelled();

    /**
     * Runs {@code action} once when the token is cancelled, or immediately if it already is. Closing the
     * registration removes the action.
     */
    Registration onCancel(Runnable action);

    /** Handle of an {@link #onCancel} registration. */
    @FunctionalInterface
    interface Registration extends AutoCloseable {
        @Override
        void close();
    }
}

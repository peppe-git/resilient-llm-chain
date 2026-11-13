// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Runs a chain call on a virtual thread, so the test thread can drive the clock meanwhile. */
final class Async {

    private Async() {
    }

    static <T> CompletableFuture<T> run(Supplier<T> body) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                future.complete(body.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /** Joins with a real-time safety net: a broken test fails instead of hanging. */
    static <T> T join(CompletableFuture<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }
}

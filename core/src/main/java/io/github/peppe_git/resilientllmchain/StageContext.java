// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

import java.util.Map;

/** What a provider sees of the chain while it serves one call. */
public interface StageContext {

    /** The stage being executed. */
    StageRef stage();

    /** Attempt number on this stage, starting at 1; retries of transient errors increment it. */
    int attempt();

    /** True when someone is waiting for the answer (see {@link CallOptions#interactive()}). */
    boolean interactive();

    /** Cancellation of the whole chain call. Providers may poll it during long operations. */
    CancellationToken cancellation();

    /** Labels of the call, as given in {@link CallOptions#labels()}. */
    Map<String, String> labels();

    /**
     * Streams a piece of text to the caller's {@link StreamListener}, if any. Only the first stage that
     * emits owns the stream; pieces from other stages are dropped. A no-op when the call has no listener,
     * so providers can always call it.
     */
    void emit(String text);
}

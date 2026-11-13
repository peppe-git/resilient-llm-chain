// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain;

/**
 * Receives a streamed preview of the answer. Calls come from stage threads but are serialised: never
 * two at once for the same chain call. Nothing reaches the listener after the chain call returns.
 *
 * <p>The preview is a preview: the final text is the one in {@link ChainResult#text()}.
 */
public interface StreamListener {

    /** A piece of text to append to the preview. */
    void delta(String text);

    /** Discard the preview so far: the owning stage failed or restarted its generation. */
    void reset();
}

// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.error;

import io.github.peppe_git.resilientllmchain.StageRef;

/** A provider returned no result or a blank text. Treated as a failed call. */
public class EmptyOutputException extends RuntimeException {

    public EmptyOutputException(StageRef stage) {
        super(stage + ": empty answer");
    }
}

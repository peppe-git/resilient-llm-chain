// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.error;

import io.github.peppe_git.resilientllmchain.StageRef;

/** An answer was rejected by the output validator. */
public class OutputRejectedException extends RuntimeException {

    private final transient StageRef stage;
    private final String defect;

    public OutputRejectedException(StageRef stage, String defect) {
        super(stage + ": output rejected: " + defect);
        this.stage = stage;
        this.defect = defect;
    }

    public StageRef stage() {
        return stage;
    }

    public String defect() {
        return defect;
    }
}

// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.error;

import io.github.peppe_git.resilientllmchain.StageRef;
import java.time.Duration;

/**
 * A provider call exceeded the chain's total call timeout and was abandoned. The calling thread is
 * interrupted, but a blocking socket read may keep running until the provider closes the connection.
 */
public class CallTimeoutException extends RuntimeException {

    public CallTimeoutException(StageRef stage, Duration timeout) {
        super(stage + ": no answer within " + timeout.toMillis() + " ms, call abandoned");
    }
}

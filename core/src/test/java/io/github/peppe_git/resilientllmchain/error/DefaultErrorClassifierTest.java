// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.error;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.peppe_git.resilientllmchain.StageRef;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class DefaultErrorClassifierTest {

    private final ErrorClassifier classifier =
            new DefaultErrorClassifier(Clock.fixed(Instant.ofEpochSecond(1_000_000_000L), ZoneOffset.UTC));

    private static ProviderHttpException http(int status, String retryAfter) {
        return new ProviderHttpException("p", status, retryAfter, "body");
    }

    @Test
    void notFoundAndGone_arePermanent() {
        assertThat(classifier.classify(http(404, null)).kind()).isEqualTo(ErrorKind.PERMANENT);
        assertThat(classifier.classify(http(410, null)).kind()).isEqualTo(ErrorKind.PERMANENT);
    }

    @Test
    void rateLimitsAndServerErrors_areRetryable_withTheirRetryAfter() {
        ErrorClassification limited = classifier.classify(http(429, "3"));
        assertThat(limited.kind()).isEqualTo(ErrorKind.RETRYABLE);
        assertThat(limited.retryAfter()).isEqualTo(Duration.ofSeconds(3));
        assertThat(limited.generationStarted()).isFalse();

        assertThat(classifier.classify(http(503, null)).retryAfter()).isNull();
        assertThat(classifier.classify(http(500, "Sun, 09 Sep 2001 01:46:50 GMT")).retryAfter())
                .isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void otherStatuses_areFailures_thatSpentNothing() {
        for (int status : new int[] {400, 401, 402, 403, 422}) {
            ErrorClassification classification = classifier.classify(http(status, null));
            assertThat(classification.kind()).as("status %d", status).isEqualTo(ErrorKind.FAILURE);
            assertThat(classification.generationStarted()).isFalse();
        }
    }

    @Test
    void theStatusWins_evenWhenWrapped() {
        assertThat(classifier.classify(new IllegalStateException("wrapped", http(404, null))).kind())
                .isEqualTo(ErrorKind.PERMANENT);
    }

    @Test
    void failuresBeforeTheRequestWasSent_spentNothing() {
        assertThat(classifier.classify(new ConnectException("refused")).generationStarted()).isFalse();
        assertThat(classifier.classify(new UnknownHostException("nowhere")).generationStarted()).isFalse();
        assertThat(classifier.classify(new HttpConnectTimeoutException("slow")).generationStarted()).isFalse();
        assertThat(classifier.classify(new SocketTimeoutException("Connect timed out")).generationStarted()).isFalse();
        assertThat(classifier.classify(new UncheckedIOException(new ConnectException("x"))).generationStarted()).isFalse();
    }

    @Test
    void abandonedCalls_areAssumedToHaveSpent() {
        ErrorClassification timeout = classifier.classify(
                new CallTimeoutException(new StageRef("p", "m"), Duration.ofSeconds(1)));
        assertThat(timeout.kind()).isEqualTo(ErrorKind.FAILURE);
        assertThat(timeout.generationStarted()).isTrue();
        assertThat(classifier.classify(new SocketTimeoutException("Read timed out")).generationStarted()).isTrue();
        assertThat(classifier.classify(new IOException("premature end of body")).generationStarted()).isTrue();
    }

    @Test
    @Timeout(value = 5, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aCircularCauseChain_doesNotLoopForever() {
        IllegalStateException first = new IllegalStateException("first");
        IllegalStateException second = new IllegalStateException("second", first);
        first.initCause(second);

        assertThat(classifier.classify(first)).isEqualTo(ErrorClassification.failure(true));
    }

    @Test
    void contentRefusals_leaveTheBreakerAlone() {
        ErrorClassification refused = classifier.classify(new ContentRefusedException("declined", false));

        assertThat(refused.kind()).isEqualTo(ErrorKind.CONTENT);
        assertThat(refused.generationStarted()).isFalse();
    }

    @Test
    void theExceptionKeepsAOneLineExcerptOfTheBody() {
        ProviderHttpException e = new ProviderHttpException("p", 410, null, "{\n  \"detail\":\n \"retired\" }" + "x".repeat(500));

        assertThat(e.bodyExcerpt()).startsWith("{ \"detail\": \"retired\" }").hasSize(203);
        assertThat(e.getMessage()).startsWith("HTTP 410 from p: {");
    }
}

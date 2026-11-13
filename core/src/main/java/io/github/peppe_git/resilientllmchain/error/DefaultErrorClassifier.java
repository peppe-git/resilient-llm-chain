// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.error;

import io.github.peppe_git.resilientllmchain.retry.RetryAfter;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.time.Clock;
import java.util.Locale;

/**
 * Classifies the library's own exceptions and the JDK's network exceptions. Walks the cause chain; the
 * first recognised exception decides.
 *
 * <table>
 *   <caption>Rules</caption>
 *   <tr><th>Failure</th><th>Kind</th><th>Tokens probably spent</th></tr>
 *   <tr><td>{@link ProviderHttpException} 404, 410</td><td>PERMANENT</td><td>no</td></tr>
 *   <tr><td>{@link ProviderHttpException} 429, 5xx</td><td>RETRYABLE (with {@code Retry-After})</td><td>no</td></tr>
 *   <tr><td>{@link ProviderHttpException}, other status</td><td>FAILURE</td><td>no</td></tr>
 *   <tr><td>{@link ContentRefusedException}</td><td>CONTENT</td><td>as declared by the exception</td></tr>
 *   <tr><td>connect failure, unknown host, connect timeout</td><td>FAILURE</td><td>no</td></tr>
 *   <tr><td>{@link CallTimeoutException}, read timeout, anything else</td><td>FAILURE</td><td>yes</td></tr>
 * </table>
 *
 * <p>401 and 403 stay ordinary failures on purpose: authentication is a provider-wide problem, and a
 * long cooldown would also close models that work. 400 can depend on the single request.
 */
public final class DefaultErrorClassifier implements ErrorClassifier {

    private static final int MAX_CAUSE_DEPTH = 32;

    static final DefaultErrorClassifier INSTANCE = new DefaultErrorClassifier(Clock.systemUTC());

    private final Clock clock;

    /** @param clock used to turn an HTTP-date {@code Retry-After} into a delay */
    public DefaultErrorClassifier(Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ErrorClassification classify(Throwable failure) {
        // Bounded walk: a cause chain can be circular (A caused by B caused by A).
        int depth = 0;
        for (Throwable t = failure; t != null && depth < MAX_CAUSE_DEPTH;
                t = t.getCause() == t ? null : t.getCause(), depth++) {
            if (t instanceof ProviderHttpException http) {
                return classifyStatus(http);
            }
            if (t instanceof ContentRefusedException refused) {
                return ErrorClassification.content(refused.generationStarted());
            }
            if (t instanceof CallTimeoutException) {
                return ErrorClassification.failure(true);
            }
            if (beforeTheRequestWasSent(t)) {
                return ErrorClassification.failure(false);
            }
        }
        return ErrorClassification.failure(true);
    }

    private ErrorClassification classifyStatus(ProviderHttpException http) {
        int status = http.statusCode();
        if (status == 404 || status == 410) {
            return ErrorClassification.permanent();
        }
        if (status == 429 || status >= 500) {
            return ErrorClassification.retryable(
                    RetryAfter.parse(http.retryAfterHeader(), clock.instant()).orElse(null));
        }
        return ErrorClassification.failure(false);
    }

    private static boolean beforeTheRequestWasSent(Throwable t) {
        if (t instanceof ConnectException || t instanceof UnknownHostException
                || t instanceof NoRouteToHostException || t instanceof HttpConnectTimeoutException) {
            return true;
        }
        // The JDK uses the same SocketTimeoutException for "could not connect" (nothing spent) and for
        // "connected, then waited in vain" (tokens spent): only the message tells them apart.
        if (t instanceof SocketTimeoutException || t instanceof InterruptedIOException) {
            String message = t.getMessage();
            return message != null && message.toLowerCase(Locale.ROOT).contains("connect");
        }
        return false;
    }
}

// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.error;

/**
 * An HTTP error status from a provider. Adapters throw it so that {@link DefaultErrorClassifier} can
 * route retries, cooldowns and fallbacks without depending on any HTTP client library.
 */
public class ProviderHttpException extends RuntimeException {

    private static final int EXCERPT_CHARS = 200;

    private final int statusCode;
    private final String retryAfterHeader;
    private final String bodyExcerpt;

    /**
     * @param provider         provider name, for the message
     * @param statusCode       HTTP status
     * @param retryAfterHeader raw {@code Retry-After} value, or {@code null}
     * @param body             response body, or {@code null}; only a one-line excerpt is kept
     */
    public ProviderHttpException(String provider, int statusCode, String retryAfterHeader, String body) {
        super(message(provider, statusCode, excerpt(body)));
        this.statusCode = statusCode;
        this.retryAfterHeader = retryAfterHeader;
        this.bodyExcerpt = excerpt(body);
    }

    public int statusCode() {
        return statusCode;
    }

    public String retryAfterHeader() {
        return retryAfterHeader;
    }

    /** First characters of the body on one line: providers often write the reason there. */
    public String bodyExcerpt() {
        return bodyExcerpt;
    }

    private static String message(String provider, int status, String excerpt) {
        return "HTTP " + status + " from " + provider + (excerpt.isEmpty() ? "" : ": " + excerpt);
    }

    static String excerpt(String body) {
        if (body == null) {
            return "";
        }
        String text = body.replaceAll("\\s+", " ").strip();
        return text.length() > EXCERPT_CHARS ? text.substring(0, EXCERPT_CHARS) + "..." : text;
    }
}

// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.retry;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * Reads an HTTP {@code Retry-After} value (RFC 9110): either delay-seconds or an HTTP-date.
 *
 * <p>It is an instruction from the server, not an estimate: callers must not add jitter to it. They
 * must cap it, though, or the server decides how long a local resource stays busy.
 */
public final class RetryAfter {

    private RetryAfter() {
    }

    /**
     * The requested wait, or empty when the value is missing or unreadable. Negative values and dates in
     * the past become zero.
     *
     * @param value the header value
     * @param now   the current instant, to turn an HTTP-date into a delay
     */
    public static Optional<Duration> parse(String value, Instant now) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String text = value.strip();
        try {
            long seconds = Long.parseLong(text);
            return Optional.of(Duration.ofSeconds(Math.max(0L, seconds)));
        } catch (NumberFormatException notSeconds) {
            // fall through to the HTTP-date form
        }
        try {
            Instant at = ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration wait = Duration.between(now, at);
            return Optional.of(wait.isNegative() ? Duration.ZERO : wait);
        } catch (DateTimeParseException unreadable) {
            return Optional.empty();
        }
    }
}

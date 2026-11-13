// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.resilientllmchain.retry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class RetryAfterTest {

    private static final Instant NOW = Instant.ofEpochSecond(1_000_000_000L);

    @Test
    void delaySeconds() {
        assertThat(RetryAfter.parse("7", NOW)).contains(Duration.ofSeconds(7));
        assertThat(RetryAfter.parse(" 0 ", NOW)).contains(Duration.ZERO);
        assertThat(RetryAfter.parse("-3", NOW)).contains(Duration.ZERO);
    }

    @Test
    void httpDate_isTurnedIntoADelay() {
        // 1_000_000_000 epoch seconds plus 90 s, in IMF-fixdate form.
        assertThat(RetryAfter.parse("Sun, 09 Sep 2001 01:48:10 GMT", NOW)).contains(Duration.ofSeconds(90));
    }

    @Test
    void aDateInThePast_meansNow() {
        assertThat(RetryAfter.parse("Sun, 09 Sep 2001 01:00:00 GMT", NOW)).contains(Duration.ZERO);
    }

    @Test
    void missingOrUnreadable_isEmpty() {
        assertThat(RetryAfter.parse(null, NOW)).isEmpty();
        assertThat(RetryAfter.parse("  ", NOW)).isEmpty();
        assertThat(RetryAfter.parse("soon", NOW)).isEmpty();
    }
}

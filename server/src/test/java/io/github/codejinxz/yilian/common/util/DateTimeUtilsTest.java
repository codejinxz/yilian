package io.github.codejinxz.yilian.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

/** DateTimeUtils 的过期边界：恰好到期算过期，缺参数一律失败关闭。 */
class DateTimeUtilsTest {

    private static final Instant ISSUED_AT = Instant.parse("2025-01-01T00:00:00Z");

    @Test
    void nowIsCloseToSystemClock() {
        Instant before = Instant.now();
        Instant actual = DateTimeUtils.now();
        Instant after = Instant.now();

        assertThat(actual).isBetween(before, after);
    }

    @Test
    void expiredWhenIssuedAtIsNull() {
        assertThat(DateTimeUtils.isExpired(null, Duration.ofMinutes(5), Instant.now())).isTrue();
    }

    @Test
    void expiredWhenTtlIsNull() {
        assertThat(DateTimeUtils.isExpired(Instant.now(), null, Instant.now())).isTrue();
    }

    @Test
    void expiredWhenNowIsNull() {
        assertThat(DateTimeUtils.isExpired(Instant.now(), Duration.ofMinutes(5), null)).isTrue();
    }

    @Test
    void expiredExactlyAtExpiryInstant() {
        Instant now = ISSUED_AT.plus(Duration.ofMinutes(5));

        assertThat(DateTimeUtils.isExpired(ISSUED_AT, Duration.ofMinutes(5), now)).isTrue();
    }

    @Test
    void notExpiredOneNanosecondBeforeExpiry() {
        Instant now = ISSUED_AT.plus(Duration.ofMinutes(5)).minusNanos(1);

        assertThat(DateTimeUtils.isExpired(ISSUED_AT, Duration.ofMinutes(5), now)).isFalse();
    }

    @Test
    void expiredOneNanosecondAfterExpiry() {
        Instant now = ISSUED_AT.plus(Duration.ofMinutes(5)).plusNanos(1);

        assertThat(DateTimeUtils.isExpired(ISSUED_AT, Duration.ofMinutes(5), now)).isTrue();
    }

    @Test
    void notExpiredBeforeExpiry() {
        assertThat(DateTimeUtils.isExpired(ISSUED_AT, Duration.ofHours(1), ISSUED_AT)).isFalse();
    }

    @Test
    void expiredWhenTtlIsZero() {
        assertThat(DateTimeUtils.isExpired(ISSUED_AT, Duration.ZERO, ISSUED_AT)).isTrue();
    }

    @Test
    void expiredWhenTtlIsNegative() {
        assertThat(DateTimeUtils.isExpired(ISSUED_AT, Duration.ofSeconds(-1), ISSUED_AT)).isTrue();
    }

    @Test
    void notExpiredWhenIssuedAtIsInTheFuture() {
        assertThat(
                        DateTimeUtils.isExpired(
                                ISSUED_AT.plusSeconds(60), Duration.ofSeconds(30), ISSUED_AT))
                .isFalse();
    }

    @Test
    void twoArgOverloadUsesCurrentClock() {
        assertThat(DateTimeUtils.isExpired(Instant.now(), Duration.ofHours(1))).isFalse();
        assertThat(DateTimeUtils.isExpired(Instant.now().minusSeconds(120), Duration.ofMinutes(1)))
                .isTrue();
    }

    @Test
    void twoArgOverloadTreatsMissingInputAsExpired() {
        assertThat(DateTimeUtils.isExpired(null, Duration.ofHours(1))).isTrue();
        assertThat(DateTimeUtils.isExpired(Instant.now(), null)).isTrue();
        assertThat(DateTimeUtils.isExpired(null, null)).isTrue();
    }
}

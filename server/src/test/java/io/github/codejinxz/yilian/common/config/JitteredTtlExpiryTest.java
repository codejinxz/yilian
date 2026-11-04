package io.github.codejinxz.yilian.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.time.Duration;

/** {@link JitteredTtlExpiry} 的抖动边界与负缓存短 TTL 单测（防雪崩/防穿透）。 */
class JitteredTtlExpiryTest {

    private static final Duration BASE_TTL = Duration.ofMinutes(10);

    private static final Duration NEGATIVE_TTL = Duration.ofSeconds(60);

    private static final long NOW_NANOS = 1_000_000_000L;

    private static final Object KEY = new Object();

    /** 抖动下界：抖动源返回 0.0 时 TTL 为基准的 0.9 倍。 */
    @Test
    void jitterLowerBoundIsNineTenthsOfBase_ac3() {
        var expiry = new JitteredTtlExpiry(BASE_TTL, NEGATIVE_TTL, () -> 0.0);

        long expiryPoint = expiry.expireAfterCreate(KEY, new Object(), NOW_NANOS);

        long expected = NOW_NANOS + (long) (BASE_TTL.toNanos() * 0.9);
        assertThat(expiryPoint).isEqualTo(expected);
    }

    /** 抖动上界：抖动源返回 1.0 时 TTL 为基准的 1.1 倍（生产源 Math::random 取 [0,1)，1.0 仅测试可达）。 */
    @Test
    void jitterUpperBoundIsElevenTenthsOfBase_ac3() {
        var expiry = new JitteredTtlExpiry(BASE_TTL, NEGATIVE_TTL, () -> 1.0);

        long expiryPoint = expiry.expireAfterCreate(KEY, new Object(), NOW_NANOS);

        long expected = NOW_NANOS + (long) (BASE_TTL.toNanos() * 1.1);
        assertThat(expiryPoint).isEqualTo(expected);
    }

    /** 抖动中值：0.5 时恰为基准 TTL（0.9 + 0.2 × 0.5 = 1.0）。 */
    @Test
    void jitterMidpointEqualsBaseTtl_ac3() {
        var expiry = new JitteredTtlExpiry(BASE_TTL, NEGATIVE_TTL, () -> 0.5);

        long expiryPoint = expiry.expireAfterCreate(KEY, new Object(), NOW_NANOS);

        assertThat(expiryPoint).isEqualTo(NOW_NANOS + BASE_TTL.toNanos());
    }

    /** 负缓存条目使用独立短 TTL：正缓存不受影响，负条目按 NEGATIVE_TTL 抖动。 */
    @Test
    void negativeEntryUsesShortTtl_ac3() {
        var expiry = new JitteredTtlExpiry(BASE_TTL, NEGATIVE_TTL, () -> 0.5);
        Object negative = new TestNegativeEntry();

        long positivePoint = expiry.expireAfterCreate(KEY, new Object(), NOW_NANOS);
        long negativePoint = expiry.expireAfterCreate(KEY, negative, NOW_NANOS);

        assertThat(positivePoint).isEqualTo(NOW_NANOS + BASE_TTL.toNanos());
        assertThat(negativePoint).isEqualTo(NOW_NANOS + NEGATIVE_TTL.toNanos());
    }

    /** 读取不改变到期点：返回当前剩余时长，热点键不因被读而续期。 */
    @Test
    void readKeepsRemainingDuration_ac3() {
        var expiry = new JitteredTtlExpiry(BASE_TTL, NEGATIVE_TTL, () -> 0.5);

        long remaining = expiry.expireAfterRead(KEY, new Object(), NOW_NANOS, 123_456L);

        assertThat(remaining).isEqualTo(123_456L);
    }

    /** 覆盖写按新值重新确定到期点（而非沿用旧剩余时长）。 */
    @Test
    void updateRecomputesExpiry_ac3() {
        var expiry = new JitteredTtlExpiry(BASE_TTL, NEGATIVE_TTL, () -> 0.5);

        long expiryPoint = expiry.expireAfterUpdate(KEY, new Object(), NOW_NANOS, 1_000L);

        assertThat(expiryPoint).isEqualTo(NOW_NANOS + BASE_TTL.toNanos());
    }

    /** 负缓存标记的测试替身。 */
    private static final class TestNegativeEntry implements NegativeCacheEntry {}
}

package io.github.codejinxz.yilian.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;

import javax.crypto.SecretKey;

/** JWT 密钥与 access token TTL 的启动校验。 */
class JwtConfigurationTest {

    private static final String STRONG_SECRET =
            "unit-test-jwt-secret-0123456789abcdef0123456789abcdef";

    private final JwtConfiguration configuration = new JwtConfiguration();

    @Test
    void rejectsShortSecret() {
        JwtProperties properties = new JwtProperties("yilian", "short", Duration.ofMinutes(15));

        assertThatThrownBy(() -> configuration.jwtSecretKey(properties, new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 字节");
    }

    @Test
    void rejectsLocalSecretOutsideLocalProfile() {
        JwtProperties properties =
                new JwtProperties(
                        "yilian",
                        "local-dev-only-yilian-jwt-secret-0123456789abcdef",
                        Duration.ofMinutes(15));

        assertThatThrownBy(() -> configuration.jwtSecretKey(properties, new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local profile");
    }

    @Test
    void rejectsAccessTokenTtlOutsideTenToFifteenMinutes() {
        JwtProperties properties =
                new JwtProperties("yilian", STRONG_SECRET, Duration.ofMinutes(30));

        assertThatThrownBy(() -> configuration.jwtSecretKey(properties, new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("10–15 分钟");
    }

    @Test
    void acceptsStrongSecretAndFifteenMinuteTtl() {
        JwtProperties properties =
                new JwtProperties("yilian", STRONG_SECRET, Duration.ofMinutes(15));

        SecretKey key = configuration.jwtSecretKey(properties, new MockEnvironment());

        assertThat(key.getAlgorithm()).isEqualTo("HmacSHA256");
        assertThat(key.getEncoded()).hasSizeGreaterThanOrEqualTo(32);
    }
}

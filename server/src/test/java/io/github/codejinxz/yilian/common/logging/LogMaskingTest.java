package io.github.codejinxz.yilian.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 日志脱敏规则：id 只保留末两位，密钥只保留末四位，文本按上限截断，敏感字段统一遮盖。 */
class LogMaskingTest {

    @Test
    void masksLongIds() {
        assertThat(LogMasking.maskUserId(123456L)).isEqualTo("u***56");
    }

    @Test
    void masksShortIds() {
        assertThat(LogMasking.maskUserId(7L)).isEqualTo("u***");
    }

    @Test
    void anonymousHasNoId() {
        assertThat(LogMasking.maskUserId(null)).isEqualTo("anonymous");
    }

    @Test
    void maskSecretReturnsPlaceholderForNullOrBlank() {
        assertThat(LogMasking.maskSecret(null)).isEqualTo("***");
        assertThat(LogMasking.maskSecret("")).isEqualTo("***");
        assertThat(LogMasking.maskSecret("   ")).isEqualTo("***");
    }

    @Test
    void maskSecretHidesShortValuesCompletely() {
        assertThat(LogMasking.maskSecret("abc")).isEqualTo("***");
        assertThat(LogMasking.maskSecret("abcd")).isEqualTo("***");
    }

    @Test
    void maskSecretKeepsOnlyLastFourCharacters() {
        assertThat(LogMasking.maskSecret("abcde")).isEqualTo("***bcde");
        assertThat(LogMasking.maskSecret("sk-live-1234567890")).isEqualTo("***7890");
    }

    @Test
    void maskTextReturnsNullForNullValue() {
        assertThat(LogMasking.maskText(null, 10)).isNull();
    }

    @Test
    void maskTextReturnsEmptyForNonPositiveMaxLength() {
        assertThat(LogMasking.maskText("abc", 0)).isEmpty();
        assertThat(LogMasking.maskText("abc", -3)).isEmpty();
    }

    @Test
    void maskTextKeepsTextWithinLimit() {
        assertThat(LogMasking.maskText("短句", 10)).isEqualTo("短句");
        assertThat(LogMasking.maskText("0123456789", 10)).isEqualTo("0123456789");
    }

    @Test
    void maskTextTruncatesAndMarksOmission() {
        assertThat(LogMasking.maskText("这是一段很长的完整译文", 4)).isEqualTo("这是一段…");
        assertThat(LogMasking.maskText("abcdef", 1)).isEqualTo("a…");
    }

    @Test
    void sanitizeReturnsNullAndEmptyUnchanged() {
        assertThat(LogMasking.sanitize(null)).isNull();
        assertThat(LogMasking.sanitize("")).isEmpty();
    }

    @Test
    void sanitizeKeepsCleanTextUnchanged() {
        String message = "request completed module=auth status=200";

        assertThat(LogMasking.sanitize(message)).isSameAs(message);
    }

    @Test
    void sanitizeKeepsOrdinaryProseUnchanged() {
        String message = "authorization failed for user amy";

        assertThat(LogMasking.sanitize(message)).isSameAs(message);
    }

    @Test
    void sanitizeMasksJsonPassword() {
        assertThat(LogMasking.sanitize("{\"username\":\"amy\",\"password\":\"hunter2\"}"))
                .isEqualTo("{\"username\":\"amy\",\"password\":\"***\"}");
    }

    @Test
    void sanitizeMasksJsonTokensWithSpaces() {
        String sanitized =
                LogMasking.sanitize("{\"password\": \"hunter2\", \"accessToken\": \"abc-123\"}");

        assertThat(sanitized).isEqualTo("{\"password\": \"***\", \"accessToken\": \"***\"}");
    }

    @Test
    void sanitizeMasksQueryParameters() {
        assertThat(LogMasking.sanitize("/api/auth/login?password=hunter2&token=abc&page=2"))
                .isEqualTo("/api/auth/login?password=***&token=***&page=2");
    }

    @Test
    void sanitizeKeepsOtherQueryParameters() {
        assertThat(LogMasking.sanitize("/api/practice?secret=s3cr3t&page=2&size=20"))
                .isEqualTo("/api/practice?secret=***&page=2&size=20");
    }

    @Test
    void sanitizeMasksBearerAuthorizationAndKeepsScheme() {
        assertThat(LogMasking.sanitize("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig"))
                .isEqualTo("Authorization: Bearer ***");
    }

    @Test
    void sanitizeMasksQuotedBearerAuthorization() {
        assertThat(LogMasking.sanitize("{\"authorization\": \"Bearer abc.def\"}"))
                .isEqualTo("{\"authorization\": \"Bearer ***\"}");
    }

    @Test
    void sanitizeMasksNonBearerAuthorization() {
        assertThat(LogMasking.sanitize("Authorization: Basic dXNlcjpwYXNz"))
                .isEqualTo("Authorization: Basic ***");
    }

    @Test
    void sanitizeIsCaseInsensitive() {
        assertThat(LogMasking.sanitize("PASSWORD=hunter2&Access_Token=abc"))
                .isEqualTo("PASSWORD=***&Access_Token=***");
    }

    @Test
    void sanitizeMasksEveryKnownField() {
        String message =
                "password=one&passwd=two&secret=three&token=four"
                        + "&refreshToken=five&access-token=six";

        assertThat(LogMasking.sanitize(message))
                .isEqualTo(
                        "password=***&passwd=***&secret=***&token=***"
                                + "&refreshToken=***&access-token=***");
    }

    @Test
    void sanitizeMasksPrefixedSecretKey() {
        assertThat(LogMasking.sanitize("client_secret=abcd1234")).isEqualTo("client_secret=***");
    }

    @Test
    void sanitizeMasksStandaloneBearerToken() {
        assertThat(LogMasking.sanitize("call failed with Bearer abc.def"))
                .isEqualTo("call failed with Bearer ***");
    }

    @Test
    void sanitizeDoesNotLeakBearerInsideOtherField() {
        assertThat(LogMasking.sanitize("token: Bearer abc.def")).doesNotContain("abc.def");
    }

    @Test
    void sanitizeDoesNotLeakOriginalSecrets() {
        String sanitized =
                LogMasking.sanitize(
                        "password=hunter2&accessToken=abc-123 Authorization: Bearer xyz.789");

        assertThat(sanitized)
                .doesNotContain("hunter2")
                .doesNotContain("abc-123")
                .doesNotContain("xyz.789");
    }
}

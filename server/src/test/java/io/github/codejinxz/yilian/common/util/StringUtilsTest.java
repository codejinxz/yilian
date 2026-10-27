package io.github.codejinxz.yilian.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** StringUtils 的空白判定、兜底与截断边界。 */
class StringUtilsTest {

    @Test
    void isBlankTreatsNullAndWhitespaceAsBlank() {
        assertThat(StringUtils.isBlank(null)).isTrue();
        assertThat(StringUtils.isBlank("")).isTrue();
        assertThat(StringUtils.isBlank("   ")).isTrue();
        assertThat(StringUtils.isBlank("\t\n\r")).isTrue();
        assertThat(StringUtils.isBlank("\u3000")).isTrue();
    }

    @Test
    void isBlankRejectsTextWithWhitespaceAround() {
        assertThat(StringUtils.isBlank(" a ")).isFalse();
        assertThat(StringUtils.isBlank("0")).isFalse();
    }

    @Test
    void isNotBlankIsTheInverse() {
        assertThat(StringUtils.isNotBlank(" a ")).isTrue();
        assertThat(StringUtils.isNotBlank("0")).isTrue();
        assertThat(StringUtils.isNotBlank(null)).isFalse();
        assertThat(StringUtils.isNotBlank("   ")).isFalse();
    }

    @Test
    void defaultIfBlankReturnsFallbackForBlankInput() {
        assertThat(StringUtils.defaultIfBlank(null, "fallback")).isEqualTo("fallback");
        assertThat(StringUtils.defaultIfBlank("", "fallback")).isEqualTo("fallback");
        assertThat(StringUtils.defaultIfBlank("   ", "fallback")).isEqualTo("fallback");
    }

    @Test
    void defaultIfBlankKeepsNonBlankValueUntouched() {
        assertThat(StringUtils.defaultIfBlank(" value ", "fallback")).isEqualTo(" value ");
    }

    @Test
    void defaultIfBlankAllowsNullFallback() {
        assertThat(StringUtils.defaultIfBlank("", null)).isNull();
        assertThat(StringUtils.defaultIfBlank(null, null)).isNull();
    }

    @Test
    void truncateReturnsNullForNullInput() {
        assertThat(StringUtils.truncate(null, 10)).isNull();
        assertThat(StringUtils.truncate(null, 0)).isNull();
        assertThat(StringUtils.truncate(null, -1)).isNull();
    }

    @Test
    void truncateReturnsEmptyStringForNonPositiveMaxLength() {
        assertThat(StringUtils.truncate("abc", 0)).isEmpty();
        assertThat(StringUtils.truncate("abc", -5)).isEmpty();
        assertThat(StringUtils.truncate("", 0)).isEmpty();
    }

    @Test
    void truncateKeepsValuesWithinLimit() {
        assertThat(StringUtils.truncate("abc", 5)).isEqualTo("abc");
        assertThat(StringUtils.truncate("abc", 3)).isEqualTo("abc");
        assertThat(StringUtils.truncate("", 10)).isEmpty();
        assertThat(StringUtils.truncate("abc", Integer.MAX_VALUE)).isEqualTo("abc");
    }

    @Test
    void truncateCutsToExactLengthWhenTooLong() {
        assertThat(StringUtils.truncate("abcdef", 4)).isEqualTo("abcd");
        assertThat(StringUtils.truncate("abcdef", 1)).isEqualTo("a");
        assertThat(StringUtils.truncate("abcdef", 5)).hasSize(5);
    }

    @Test
    void truncateCountsUtf16CharsWithoutOvershooting() {
        assertThat(StringUtils.truncate("你好世界", 2)).isEqualTo("你好");
        assertThat(StringUtils.truncate("你好世界", 4)).isEqualTo("你好世界");
    }
}

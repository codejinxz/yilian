package io.github.codejinxz.yilian.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** UsernameUtils 的长度与字符白名单边界。 */
class UsernameUtilsTest {

    @Test
    void acceptsAllowedShapes() {
        assertThat(UsernameUtils.isValid("abc")).isTrue();
        assertThat(UsernameUtils.isValid("ABC123")).isTrue();
        assertThat(UsernameUtils.isValid("a.b_c-d")).isTrue();
        assertThat(UsernameUtils.isValid("123")).isTrue();
        assertThat(UsernameUtils.isValid("a".repeat(64))).isTrue();
    }

    @Test
    void rejectsNullAndEmpty() {
        assertThat(UsernameUtils.isValid(null)).isFalse();
        assertThat(UsernameUtils.isValid("")).isFalse();
    }

    @Test
    void rejectsLengthOutOfRange() {
        assertThat(UsernameUtils.isValid("ab")).isFalse();
        assertThat(UsernameUtils.isValid("a".repeat(65))).isFalse();
    }

    @Test
    void rejectsWhitespace() {
        assertThat(UsernameUtils.isValid("   ")).isFalse();
        assertThat(UsernameUtils.isValid(" ab")).isFalse();
        assertThat(UsernameUtils.isValid("ab ")).isFalse();
        assertThat(UsernameUtils.isValid("a b")).isFalse();
        assertThat(UsernameUtils.isValid("abc\n")).isFalse();
    }

    @Test
    void rejectsIllegalCharacters() {
        assertThat(UsernameUtils.isValid("abc@def")).isFalse();
        assertThat(UsernameUtils.isValid("abc$")).isFalse();
        assertThat(UsernameUtils.isValid("a/b")).isFalse();
        assertThat(UsernameUtils.isValid("a+b")).isFalse();
        assertThat(UsernameUtils.isValid("中文名")).isFalse();
    }
}

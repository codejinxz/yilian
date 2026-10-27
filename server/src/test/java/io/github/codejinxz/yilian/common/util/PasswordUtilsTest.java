package io.github.codejinxz.yilian.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** PasswordUtils 的字节口径、弱口令集合与生产强度规则。 */
class PasswordUtilsTest {

    @Test
    void bcryptLimitAndMinLengthMatchContract() {
        assertThat(PasswordUtils.BCRYPT_MAX_BYTES).isEqualTo(72);
        assertThat(PasswordUtils.MIN_PROD_LENGTH).isEqualTo(12);
    }

    @Test
    void utf8LengthHandlesNullAndEmpty() {
        assertThat(PasswordUtils.utf8Length(null)).isZero();
        assertThat(PasswordUtils.utf8Length("")).isZero();
    }

    @Test
    void utf8LengthCountsBytesNotChars() {
        assertThat(PasswordUtils.utf8Length("abc")).isEqualTo(3);
        assertThat(PasswordUtils.utf8Length("密码")).isEqualTo(6);
        assertThat(PasswordUtils.utf8Length("😀")).isEqualTo(4);
    }

    @Test
    void exceedsBcryptLimitAtByteBoundary() {
        assertThat(PasswordUtils.exceedsBcryptLimit("a".repeat(72))).isFalse();
        assertThat(PasswordUtils.exceedsBcryptLimit("a".repeat(73))).isTrue();
    }

    @Test
    void exceedsBcryptLimitCountsMultibyteCharacters() {
        assertThat(PasswordUtils.exceedsBcryptLimit("密".repeat(24))).isFalse();
        assertThat(PasswordUtils.exceedsBcryptLimit("密".repeat(25))).isTrue();
    }

    @Test
    void exceedsBcryptLimitIsFalseForNullAndEmpty() {
        assertThat(PasswordUtils.exceedsBcryptLimit(null)).isFalse();
        assertThat(PasswordUtils.exceedsBcryptLimit("")).isFalse();
    }

    @Test
    void prodWeaknessRejectsBlankPassword() {
        assertThat(PasswordUtils.prodWeakness("alice", null))
                .hasValueSatisfying(reason -> assertThat(reason).contains("不能为空"));
        assertThat(PasswordUtils.prodWeakness("alice", "")).isPresent();
        assertThat(PasswordUtils.prodWeakness("alice", "   ")).isPresent();
    }

    @Test
    void prodWeaknessRejectsShortPassword() {
        assertThat(PasswordUtils.prodWeakness("alice", "short"))
                .hasValueSatisfying(reason -> assertThat(reason).contains("12"));
        assertThat(PasswordUtils.prodWeakness("alice", "a".repeat(11)))
                .hasValueSatisfying(reason -> assertThat(reason).contains("12"));
    }

    @Test
    void prodWeaknessAcceptsExactlyMinimumLength() {
        assertThat(PasswordUtils.prodWeakness("alice", "a".repeat(12))).isEmpty();
        assertThat(PasswordUtils.prodWeakness("alice", "a".repeat(13))).isEmpty();
    }

    @Test
    void prodWeaknessRejectsPasswordEqualToUsernameIgnoringCase() {
        assertThat(PasswordUtils.prodWeakness("AliceWonderland", "alicewonderland"))
                .hasValueSatisfying(reason -> assertThat(reason).contains("用户名"));
        assertThat(PasswordUtils.prodWeakness("alicewonderland", "ALICEWONDERLAND"))
                .hasValueSatisfying(reason -> assertThat(reason).contains("用户名"));
    }

    @Test
    void prodWeaknessRejectsKnownWeakPasswords() {
        assertThat(PasswordUtils.prodWeakness("alice", "change-me-now"))
                .hasValueSatisfying(reason -> assertThat(reason).contains("常见"));
        assertThat(PasswordUtils.prodWeakness("alice", "CHANGE-ME-NOW"))
                .hasValueSatisfying(reason -> assertThat(reason).contains("常见"));
    }

    @Test
    void prodWeaknessAcceptsStrongPassword() {
        assertThat(PasswordUtils.prodWeakness("alice", "Tr0ub4dor&3x")).isEmpty();
        assertThat(PasswordUtils.prodWeakness(null, "Tr0ub4dor&3x")).isEmpty();
    }

    @Test
    void prodWeaknessReasonDoesNotLeakPassword() {
        String password = "change-me-now";

        assertThat(PasswordUtils.prodWeakness("alice", password).orElseThrow())
                .doesNotContain(password);
    }
}

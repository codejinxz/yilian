package io.github.codejinxz.yilian.common.util;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 密码相关的纯函数校验：UTF-8 字节上限、生产环境强度与弱口令识别。
 *
 * <p>只做规则判断，不负责哈希与存储；返回的中文原因可直接拼进异常消息或启动失败提示。 本类无状态、线程安全，禁止实例化。
 */
public final class PasswordUtils {

    /** BCrypt 只处理前 72 字节，超出部分会被静默忽略，因此必须在入口拒绝超长密码。 */
    public static final int BCRYPT_MAX_BYTES = 72;

    /** 生产环境密码最短长度；本地开发默认密码不受此限制。 */
    public static final int MIN_PROD_LENGTH = 12;

    /** 常见弱口令，命中即拒绝；比较时统一转小写，避免大小写变形绕过。 */
    private static final Set<String> WEAK_PASSWORDS =
            Set.of("password", "123456", "12345678", "qwerty", "admin", "letmein", "change-me-now");

    private PasswordUtils() {}

    /**
     * 计算字符串按 UTF-8 编码后的字节数，用于对齐 BCrypt 的字节口径。
     *
     * @param value 待计算的字符串，允许为 null
     * @return UTF-8 字节数；null 返回 0
     */
    public static int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 判断密码是否超过 BCrypt 可安全处理的字节上限。
     *
     * <p>超过上限时 BCrypt 会静默截断，导致不同密码可能校验通过，必须提前拒绝。
     *
     * @param value 待判断的密码，允许为 null
     * @return UTF-8 字节数大于 {@link #BCRYPT_MAX_BYTES} 时返回 true；null 返回 false
     */
    public static boolean exceedsBcryptLimit(String value) {
        return utf8Length(value) > BCRYPT_MAX_BYTES;
    }

    /**
     * 检查生产环境密码强度，按“为空 → 长度不足 → 与用户名相同 → 弱口令”的顺序给出首个原因。
     *
     * <p>比较用户名与弱口令时忽略大小写，防止大小写变形绕过规则。
     *
     * @param username 用户名，用于阻止密码与用户名相同，允许为 null
     * @param password 待检查的密码，允许为 null
     * @return 首个中文原因；密码满足全部规则时返回 {@link Optional#empty()}
     */
    public static Optional<String> prodWeakness(String username, String password) {
        if (StringUtils.isBlank(password)) {
            return Optional.of("密码不能为空");
        }
        if (password.length() < MIN_PROD_LENGTH) {
            return Optional.of("密码长度不足，至少需要 " + MIN_PROD_LENGTH + " 个字符");
        }
        if (username != null && password.equalsIgnoreCase(username)) {
            return Optional.of("密码不能与用户名相同");
        }
        if (WEAK_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) {
            return Optional.of("密码过于常见，请更换为更复杂的密码");
        }
        return Optional.empty();
    }
}

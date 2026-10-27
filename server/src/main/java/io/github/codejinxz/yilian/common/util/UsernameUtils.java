package io.github.codejinxz.yilian.common.util;

import java.util.regex.Pattern;

/**
 * 用户名格式校验，规则与数据库、登录表单保持一致，避免各层各写一份正则。
 *
 * <p>允许字母、数字、点、下划线与连字符，长度 3–64；不允许空白、中文与其它符号。 本类无状态、线程安全，禁止实例化。
 */
public final class UsernameUtils {

    /** 与前端输入提示保持一致的用户名白名单正则。 */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]{3,64}");

    private UsernameUtils() {}

    /**
     * 判断用户名是否符合 {@code [A-Za-z0-9._-]{3,64}}。
     *
     * @param username 待校验的用户名，允许为 null
     * @return 符合规则返回 true；null、空串、长度越界或含非法字符返回 false
     */
    public static boolean isValid(String username) {
        return username != null && USERNAME_PATTERN.matcher(username).matches();
    }
}

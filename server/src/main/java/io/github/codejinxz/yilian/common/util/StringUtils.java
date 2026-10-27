package io.github.codejinxz.yilian.common.util;

/**
 * 字符串判空、兜底与截断的公共工具，统一“空白”语义，避免各模块各写一套 null 判断。
 *
 * <p>所有方法对 null 都有确定行为且不抛异常，便于在参数校验、日志摘要等入口直接使用。 本类无状态、线程安全，禁止实例化。
 */
public final class StringUtils {

    private StringUtils() {}

    /**
     * 判断字符串是否为 null、空串或仅由空白字符组成。
     *
     * @param value 待判断的字符串，允许为 null
     * @return 为 null、空串或全部是空白字符时返回 true
     */
    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 判断字符串是否有实际内容，等价于 {@link #isBlank(String)} 取反。
     *
     * @param value 待判断的字符串，允许为 null
     * @return 至少含一个非空白字符时返回 true
     */
    public static boolean isNotBlank(String value) {
        return !isBlank(value);
    }

    /**
     * 为空白字符串提供兜底值，用于配置项、请求字段的默认值填充。
     *
     * @param value 原始值，允许为 null
     * @param fallback 原值为空白时采用的兜底值，允许为 null
     * @return 原值非空白时返回原值，否则返回 fallback
     */
    public static String defaultIfBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    /**
     * 按 char 数把字符串截断到指定长度，用于日志、审计摘要等必须限长的位置。
     *
     * <p>不会抛越界异常：null 原样返回，maxLength 非正数时返回空串。
     *
     * @param value 原字符串，允许为 null
     * @param maxLength 最多保留的 char 数
     * @return 截断后的字符串；null 输入返回 null，maxLength 小于等于 0 时返回空串
     */
    public static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        if (maxLength <= 0) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}

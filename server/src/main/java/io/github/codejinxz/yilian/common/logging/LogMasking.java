package io.github.codejinxz.yilian.common.logging;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志脱敏的兜底工具：标识符只保留末两位便于串联排查，密钥只保留末四位，长文本按上限截断， 自由文本中的常见敏感字段统一替换成占位符。
 *
 * <p>本类只是最后一道防线。调用方仍不得主动把密码、token、Authorization 头或完整译文写进日志； 请求体不应被记录，也不应依赖本类的事后遮盖来换取“可以打印”的许可。
 * 本类无状态、线程安全，禁止实例化。
 */
public final class LogMasking {

    /** 遮盖内容的统一占位符，避免不同调用点输出长度不一的星号。 */
    private static final String MASK = "***";

    /** 长文本被截断时追加的省略标记。 */
    private static final String ELLIPSIS = "…";

    /** 密钥脱敏时保留的末位字符数。 */
    private static final int SECRET_SUFFIX_LENGTH = 4;

    /**
     * JSON/query 中常见敏感字段的键名；长键排在前面，避免 {@code token} 抢先匹配 {@code accessToken}
     * 的一部分。值支持双引号、单引号与裸值三种形态， 字符类均为线性匹配，不含嵌套量词，不会产生灾难性回溯。
     */
    private static final Pattern SENSITIVE_FIELD =
            Pattern.compile(
                    "(?i)(password|passwd|secret|access[-_]?token|refresh[-_]?token|token)"
                            + "(\\s*[\"']?\\s*[:=]\\s*)"
                            + "(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'&,;}\\]\\[]+))");

    /** Authorization 字段，保留认证方案（如 Bearer），只遮盖其后的凭证。 */
    private static final Pattern AUTHORIZATION =
            Pattern.compile(
                    "(?i)(authorization)(\\s*[\"']?\\s*[:=]\\s*)([\"']?)"
                            + "(?:(bearer|basic|digest|apikey|token|jwt)\\s+)?"
                            + "([^\\s\"'&,;}\\]\\[]+)");

    /** 脱离 Authorization 字段单独出现的 Bearer 凭证。 */
    private static final Pattern BEARER =
            Pattern.compile("(?i)\\b(bearer)(\\s+)([A-Za-z0-9._~+/=-]+)");

    private LogMasking() {}

    /**
     * 把用户 id 转成不可反查的短标识，既能在日志中串起同一用户的多次请求，又不落明文 id。
     *
     * @param userId 用户 id；未认证时传 null
     * @return 形如 {@code u***56} 的脱敏值，匿名或 id 过短时为 {@code u***}， null 时返回 {@code anonymous}
     */
    public static String maskUserId(Long userId) {
        if (userId == null) {
            return "anonymous";
        }
        String value = Long.toString(userId);
        if (value.length() <= 2) {
            return "u***";
        }
        return "u***" + value.substring(value.length() - 2);
    }

    /**
     * 遮盖密钥类值，只保留末四位用于人工核对，其余内容一律替换成占位符。
     *
     * @param value 原始密钥，允许为 null
     * @return 长度大于 4 时返回 {@code ***} 加末四位；null、空白或长度不超过 4 时返回 {@code ***}。任何情况下都不会返回完整原值
     */
    public static String maskSecret(String value) {
        if (value == null || value.isBlank() || value.length() <= SECRET_SUFFIX_LENGTH) {
            return MASK;
        }
        return MASK + value.substring(value.length() - SECRET_SUFFIX_LENGTH);
    }

    /**
     * 把完整译文等长文本截断到指定长度并追加省略标记，防止整段内容进入日志。
     *
     * <p>按 char 数截断；null 原样返回，{@code maxLength} 非正数时返回空串，均不抛异常。
     *
     * @param value 原始文本，允许为 null
     * @param maxLength 最多保留的 char 数
     * @return 截断后的文本；超出上限时额外追加一个省略标记，因此结果可能比 maxLength 多一个字符
     */
    public static String maskText(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        if (maxLength <= 0) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + ELLIPSIS;
    }

    /**
     * 把一条文本中常见的敏感字段值替换为 {@code ***}，用于日志、审计摘要等无法完全避免 自由文本的位置。
     *
     * <p>覆盖 JSON 与 query 两种形态的 {@code password}、{@code passwd}、{@code secret}、 {@code token}、{@code
     * accessToken}、{@code refreshToken}、{@code authorization} （含 Bearer 凭证），键名大小写不敏感。没有命中任何字段时原样返回。
     *
     * <p>这是兜底而非许可：调用方仍应先判断内容是否真的需要进日志，不得主动打印敏感字段。
     *
     * @param message 原始日志文本，允许为 null
     * @return 脱敏后的文本；null 返回 null，无敏感内容时返回原字符串
     */
    public static String sanitize(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        String sanitized = replaceMatches(BEARER, message, LogMasking::maskBearer);
        sanitized = replaceMatches(AUTHORIZATION, sanitized, LogMasking::maskAuthorization);
        return replaceMatches(SENSITIVE_FIELD, sanitized, LogMasking::maskSensitiveField);
    }

    /**
     * 遮盖 Authorization 的值，认证方案（Bearer、Basic 等）保留，凭证部分替换成占位符。
     *
     * @param matcher 已命中的匹配结果
     * @return 替换后的片段
     */
    private static String maskAuthorization(Matcher matcher) {
        String scheme = matcher.group(4);
        String schemePrefix = scheme == null ? "" : scheme + " ";
        return matcher.group(1) + matcher.group(2) + matcher.group(3) + schemePrefix + MASK;
    }

    /**
     * 遮盖普通敏感字段的值，保留键名、分隔符与原有引号形态。
     *
     * @param matcher 已命中的匹配结果
     * @return 替换后的片段
     */
    private static String maskSensitiveField(Matcher matcher) {
        String key = matcher.group(1);
        String separator = matcher.group(2);
        if (matcher.group(3) != null) {
            return key + separator + '"' + MASK + '"';
        }
        if (matcher.group(4) != null) {
            return key + separator + '\'' + MASK + '\'';
        }
        return key + separator + MASK;
    }

    /**
     * 遮盖单独出现的 Bearer 凭证，保留原有空白分隔。
     *
     * @param matcher 已命中的匹配结果
     * @return 替换后的片段
     */
    private static String maskBearer(Matcher matcher) {
        return matcher.group(1) + matcher.group(2) + MASK;
    }

    /**
     * 逐段替换所有命中内容；没有任何命中时直接返回原字符串，保证“无敏感内容原样返回”。
     *
     * @param pattern 匹配模式
     * @param input 原始文本
     * @param replacement 根据匹配结果生成替换片段的函数
     * @return 替换后的文本；未命中时返回原字符串
     */
    private static String replaceMatches(
            Pattern pattern, String input, Function<Matcher, String> replacement) {
        Matcher matcher = pattern.matcher(input);
        StringBuilder result = new StringBuilder(input.length());
        int lastEnd = 0;
        boolean matched = false;
        while (matcher.find()) {
            matched = true;
            result.append(input, lastEnd, matcher.start());
            result.append(replacement.apply(matcher));
            lastEnd = matcher.end();
        }
        if (!matched) {
            return input;
        }
        result.append(input, lastEnd, input.length());
        return result.toString();
    }
}

package io.github.codejinxz.yilian.common.idempotency;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * 请求参数指纹工具。
 *
 * <p>只输出 SHA-256 十六进制摘要，不保存参数原文；{@link HttpServletRequest}、{@link HttpServletResponse}
 * 这类容器对象不参与摘要，避免把连接信息或时间戳带进 hash。 数组使用深度字符串化，普通对象依赖其 {@code toString()}，因此本摘要用于区分「同一个幂等键下的不同请求体」，
 * 不承担跨版本、跨语言的内容寻址职责。
 */
public final class IdempotencyHasher {

    private IdempotencyHasher() {}

    /**
     * 计算方法参数的 SHA-256 摘要。
     *
     * @param arguments 方法参数，允许为 null
     * @return 64 位小写十六进制摘要
     */
    public static String hash(Object[] arguments) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", impossible);
        }
        if (arguments != null) {
            for (Object argument : arguments) {
                if (argument instanceof HttpServletRequest
                        || argument instanceof HttpServletResponse) {
                    continue;
                }
                String text =
                        argument == null
                                ? "null"
                                : argument.getClass().isArray()
                                        ? Arrays.deepToString(new Object[] {argument})
                                        : String.valueOf(argument);
                digest.update(text.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}

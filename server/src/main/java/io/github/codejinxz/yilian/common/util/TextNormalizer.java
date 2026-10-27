package io.github.codejinxz.yilian.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 文本归一化与哈希：导入校验、填空判分、内容指纹共用同一套规则，避免前后端/模块各写一套。
 *
 * <p>英文比较忽略大小写、首尾空白、连续空白与句末标点；中文只折叠空白，不做同义词替换。 归一化只用于比较与指纹，不回写用户原文。
 */
public final class TextNormalizer {

    private TextNormalizer() {}

    /**
     * 归一化英文文本：转小写、折叠空白、去掉首尾与句末标点。
     *
     * @param value 原文，允许为 null
     * @return 归一化结果；null 返回空串
     */
    public static String normalizeEnglish(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return normalized.replaceAll("[\\s.,!?;:，。！？；：]+$", "");
    }

    /**
     * 归一化填空答案：只折叠空白与去首尾标点，保留大小写不敏感比较。
     *
     * @param value 用户输入，允许为 null
     * @return 归一化结果
     */
    public static String normalizeAnswer(String value) {
        String normalized = normalizeEnglish(value);
        return normalized.replaceAll("^[\\s.,!?;:，。！？；：]+", "");
    }

    /**
     * 中文文本归一化：折叠空白，不做大小写与标点处理。
     *
     * @param value 原文
     * @return 归一化结果
     */
    public static String normalizeChinese(String value) {
        return value == null ? "" : value.strip().replaceAll("\\s+", " ");
    }

    /**
     * 计算 SHA-256 十六进制摘要。
     *
     * @param value 原文
     * @return 64 位小写十六进制
     */
    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 缺少 SHA-256", exception);
        }
    }
}

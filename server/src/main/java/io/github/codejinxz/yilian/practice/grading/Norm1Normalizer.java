package io.github.codejinxz.yilian.practice.grading;

import java.text.Normalizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * norm-1 答案归一化：全角转半角、弯引号转直引号、转小写、压缩空白、去句末标点。
 *
 * <p>语义基准是前端 {@code web/src/features/practice/grading.ts} 的 {@code normalizeAnswer}，
 * 服务端逐行移植，保证本地即时反馈与服务端落库判分完全一致；版本号 {@link #VERSION}
 * 写入 {@code attempt_gradings.normalizer_version}，语义变更必须升版。
 */
public final class Norm1Normalizer {

    /** 归一化版本号；写入判分记录与幂等哈希口径，变更即判分语义变更。 */
    public static final String VERSION = "norm-1";

    private static final int FULL_WIDTH_START = 0xff01;

    private static final int FULL_WIDTH_END = 0xff5e;

    private static final int FULL_WIDTH_OFFSET = 0xfee0;

    /** JS {@code /[\u2018\u2019\u201b\u2032]/g}：单弯引号族转直引号。 */
    private static final Pattern CURLY_SINGLE = Pattern.compile("[\\u2018\\u2019\\u201b\\u2032]");

    /** JS {@code /[\u201c\u201d\u201f\u2033]/g}：双弯引号族转直引号。 */
    private static final Pattern CURLY_DOUBLE = Pattern.compile("[\\u201c\\u201d\\u201f\\u2033]");

    /**
     * JS 语义的空白：Java {@code \s} 只覆盖 ASCII 部分，其余 Unicode 空白逐项补齐，
     * 与 ECMAScript {@code String.prototype.trim} 白名单一致。
     */
    private static final String JS_WS_CLASS =
            "[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]";

    /** JS {@code /\s+/g}：连续空白压成单空格。 */
    private static final Pattern WS_RUN = Pattern.compile(JS_WS_CLASS + "+");

    /** JS {@code trim()}：去掉首尾 JS 空白（Java 的 {@code trim}/{@code strip} 白名单不同）。 */
    private static final Pattern JS_TRIM = Pattern.compile(
            "^" + JS_WS_CLASS + "+|" + JS_WS_CLASS + "+\\z");

    /** JS {@code /([.!?。！？]+)(["']?)$/}：句末标点剥掉，紧随的引号保留。 */
    private static final Pattern TRAILING_PUNCT_BEFORE_QUOTE =
            Pattern.compile("([.!?。！？]+)([\"']?)\\z");

    /** JS {@code /[.!?,;:。！？，、；：…]+$/}：其余句末标点剥掉。 */
    private static final Pattern TRAILING_PUNCTUATION =
            Pattern.compile("[.!?,;:。！？，、；：…]+\\z");

    private Norm1Normalizer() {
        // 纯函数工具类，不实例化
    }

    /**
     * 归一化答案：NFKC → 弯引号转直引号 → 全角转半角 → 小写 → 压缩空白 → 去句末标点。
     *
     * <p>与 grading.ts 的步骤顺序逐一对齐：句末标点剥离发生在压缩与 trim 之后，
     * 因此「结尾是引号」的输入会保留引号（与前端行为一致）。
     *
     * @param value 用户输入或可接受答案，null 视为空串
     * @return 用于比较的规范字符串
     */
    public static String normalizeAnswer(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        normalized = CURLY_SINGLE.matcher(normalized).replaceAll("'");
        normalized = CURLY_DOUBLE.matcher(normalized).replaceAll("\"");
        normalized = normalized.replace("\u3000", " ");

        StringBuilder converted = new StringBuilder(normalized.length());
        normalized.codePoints().forEach(
                code -> {
                    if (code >= FULL_WIDTH_START && code <= FULL_WIDTH_END) {
                        converted.appendCodePoint(code - FULL_WIDTH_OFFSET);
                    } else {
                        converted.appendCodePoint(code);
                    }
                });

        // Locale.ROOT 对齐 JS toLowerCase 的语言无关简单大小写映射（默认 Locale 会受宿主影响）。
        String lowered = converted.toString().toLowerCase(Locale.ROOT);
        String trimmed = JS_TRIM.matcher(WS_RUN.matcher(lowered).replaceAll(" ")).replaceAll("");
        String quoteKept = TRAILING_PUNCT_BEFORE_QUOTE.matcher(trimmed).replaceAll("$2");
        return JS_TRIM.matcher(TRAILING_PUNCTUATION.matcher(quoteKept).replaceAll("")).replaceAll("");
    }

    /**
     * 按空白切词；空串返回空列表。
     *
     * @param value 已归一化或原始输入
     * @return 归一化后的词序列
     */
    public static List<String> tokenizeAnswer(String value) {
        String normalized = normalizeAnswer(value);
        if (normalized.isEmpty()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : normalized.split(" ", -1)) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }
}

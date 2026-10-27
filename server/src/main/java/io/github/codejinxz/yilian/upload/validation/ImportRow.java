package io.github.codejinxz.yilian.upload.validation;

import java.util.Objects;

/**
 * 一行待校验的导入数据。
 *
 * <p>只携带行号、格式与原始行文本；不提前拆分 CSV 或反序列化 JSON，让每个 {@link ImportRowValidator} 按自己的业务需要解释内容，也方便后续题库校验器复用。
 *
 * @param lineNumber 文件内物理行号，从 1 开始，空行也占一行
 * @param format 文件格式
 * @param rawLine 原始行文本，不含换行符；可能为空行
 */
public record ImportRow(long lineNumber, ImportFileFormat format, String rawLine) {

    /**
     * 校验基本不变量，避免校验器拿到负行号或空格式。
     *
     * @throws IllegalArgumentException 行号小于 1、格式为 null 或行文本为 null 时抛出
     */
    public ImportRow {
        if (lineNumber < 1) {
            throw new IllegalArgumentException("行号必须从 1 开始");
        }
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(rawLine, "rawLine");
    }
}

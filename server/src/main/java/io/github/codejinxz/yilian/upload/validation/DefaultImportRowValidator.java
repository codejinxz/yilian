package io.github.codejinxz.yilian.upload.validation;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 默认导入行校验器：只做与业务无关的结构校验。
 *
 * <p>规则刻意保持通用：
 *
 * <ul>
 *   <li>空行或仅含空白的行 → 失败；
 *   <li>单行超过 {@value #MAX_LINE_LENGTH} 个字符 → 失败，防止单行耗尽内存；
 *   <li>{@code .json}（JSON Lines）每行必须是合法 JSON 且顶层为对象，数组、标量、非法语法都失败；
 *   <li>{@code .csv} 只做空行与长度检查，列数与字段 schema 留给后续题库校验器。
 * </ul>
 *
 * <p>BOM 由行读取层剥离；这里再兜底处理第一行的 {@code \uFEFF}，避免 Windows 编辑器保存的 UTF-8 BOM 让首行 JSON
 * 被判为非法。校验器无状态，可并发复用。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DefaultImportRowValidator implements ImportRowValidator {

    /** 单行最大字符数；题库单句远小于该值，超限通常意味着文件损坏或粘贴了整段 JSON。 */
    public static final int MAX_LINE_LENGTH = 4000;

    private final ObjectMapper objectMapper;

    /**
     * 注入全局 Jackson 配置；JSON 行校验只读语法，不做业务反序列化。
     *
     * @param objectMapper Spring Boot 提供的 Jackson 3 映射器
     */
    public DefaultImportRowValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 校验一行数据。
     *
     * @param row 行号、格式与原始行文本
     * @return 通过或带中文原因的失败结果
     */
    @Override
    public ImportValidationResult validate(ImportRow row) {
        String line = row.rawLine();
        if (row.lineNumber() == 1 && line.startsWith("\uFEFF")) {
            line = line.substring(1);
        }
        if (line.isBlank()) {
            return ImportValidationResult.invalid("空行");
        }
        if (line.length() > MAX_LINE_LENGTH) {
            return ImportValidationResult.invalid("行长度超过 " + MAX_LINE_LENGTH + " 字符");
        }
        if (row.format() == ImportFileFormat.JSON_LINES && !isJsonObject(line)) {
            return ImportValidationResult.invalid("非法 JSON 行");
        }
        return ImportValidationResult.ok();
    }

    /**
     * 判断一行文本是否为合法 JSON 对象。
     *
     * @param line 非空行文本
     * @return 解析成功且顶层为对象时返回 true
     */
    private boolean isJsonObject(String line) {
        try {
            JsonNode node = objectMapper.readTree(line);
            return node != null && node.isObject();
        } catch (JacksonException exception) {
            return false;
        }
    }
}

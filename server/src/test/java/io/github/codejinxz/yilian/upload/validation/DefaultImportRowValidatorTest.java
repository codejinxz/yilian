package io.github.codejinxz.yilian.upload.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/** 默认行校验器的结构规则测试：空行、超长、JSON Lines 语法与 BOM。 */
class DefaultImportRowValidatorTest {

    private final DefaultImportRowValidator validator =
            new DefaultImportRowValidator(new ObjectMapper());

    /** 合法 JSON 对象应通过。 */
    @Test
    void acceptsJsonObject() {
        ImportValidationResult result =
                validator.validate(
                        new ImportRow(1, ImportFileFormat.JSON_LINES, "{\"zh\":\"他越来越重视\"}"));

        assertThat(result.valid()).isTrue();
        assertThat(result.reason()).isNull();
    }

    /** 空行计为失败，原因固定为「空行」。 */
    @Test
    void rejectsBlankLine() {
        ImportValidationResult result =
                validator.validate(new ImportRow(3, ImportFileFormat.CSV, "   "));

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).isEqualTo("空行");
    }

    /** 超过单行上限直接失败，且不再尝试 JSON 解析。 */
    @Test
    void rejectsOverlongLine() {
        String line = "x".repeat(DefaultImportRowValidator.MAX_LINE_LENGTH + 1);

        ImportValidationResult result =
                validator.validate(new ImportRow(5, ImportFileFormat.CSV, line));

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("行长度超过");
    }

    /** 非法 JSON 行返回稳定中文原因。 */
    @Test
    void rejectsMalformedJsonLine() {
        ImportValidationResult result =
                validator.validate(new ImportRow(7, ImportFileFormat.JSON_LINES, "{bad json}"));

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).isEqualTo("非法 JSON 行");
    }

    /** JSON Lines 只接受对象，数组或标量都不符合逐条导入语义。 */
    @Test
    void rejectsJsonArrayAndScalar() {
        assertThat(
                        validator
                                .validate(
                                        new ImportRow(
                                                1, ImportFileFormat.JSON_LINES, "[{\"zh\":\"x\"}]"))
                                .valid())
                .isFalse();
        assertThat(
                        validator
                                .validate(new ImportRow(2, ImportFileFormat.JSON_LINES, "\"x\""))
                                .valid())
                .isFalse();
    }

    /** Windows 编辑器写入的 UTF-8 BOM 不能让首行 JSON 被误判。 */
    @Test
    void ignoresBomOnFirstLine() {
        ImportValidationResult result =
                validator.validate(
                        new ImportRow(1, ImportFileFormat.JSON_LINES, "\uFEFF{\"zh\":\"他越来越重视\"}"));

        assertThat(result.valid()).isTrue();
    }

    /** CSV 行只做结构校验，非空且不超长即通过。 */
    @Test
    void acceptsCsvLine() {
        ImportValidationResult result =
                validator.validate(
                        new ImportRow(1, ImportFileFormat.CSV, "他越来越重视,He pays more attention"));

        assertThat(result.valid()).isTrue();
    }
}

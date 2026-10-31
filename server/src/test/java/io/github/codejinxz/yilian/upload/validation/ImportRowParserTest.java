package io.github.codejinxz.yilian.upload.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/** 导入行解析器单测：JSON 数组/{items} 双形态、CSV 表头解析、行数上限与形状错误。 */
class ImportRowParserTest {

    private ImportRowParser parser;

    @BeforeEach
    void setUp() {
        parser = new ImportRowParser(new ObjectMapper());
    }

    /** JSON 顶层数组：逐行规范化 JSON 并提取源 id。 */
    @Test
    void parsesJsonArray() {
        var rows =
                parser.parse(
                        "JSON", "[{\"externalKey\":\"a1\",\"zh\":\"第一句测试。\"},{\"zh\":\"第二句测试。\"}]");

        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().rowNo()).isEqualTo(1);
        assertThat(rows.getFirst().externalKey()).isEqualTo("a1");
        assertThat(rows.get(1).externalKey()).isNull();
        assertThat(rows.getFirst().rawJson()).contains("第一句测试。");
    }

    /** JSON {items:[]} 形态：与数组等价。 */
    @Test
    void parsesItemsWrapper() {
        var rows = parser.parse("JSON", "{\"items\":[{\"zh\":\"唯一一句测试。\"}]}");

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().rawJson()).contains("唯一一句测试。");
    }

    /** JSON 既非数组也无 items：40001。 */
    @Test
    void rejectsNonArrayJson() {
        assertThatThrownBy(() -> parser.parse("JSON", "{\"zh\":\"对象但无数组\"}"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.MALFORMED_REQUEST));
    }

    /** JSON 语法错误：40001。 */
    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> parser.parse("JSON", "[{broken"))
                .isInstanceOf(BusinessException.class);
    }

    /** CSV：表头 + 数据行；blank/tags 单元格按 JSON 解析（chunks 列含逗号受朴素分割限制，走字符串降级）。 */
    @Test
    void parsesCsvRowsAndJsonCells() {
        var rows =
                parser.parse(
                        "CSV",
                        "externalKey,zh,ref,note,blank,tags\n"
                                + "s_1,第一句测试。,First test sentence here.,这是解析说明满足二十字的最低长度要求。,[0],[\"代词\"]");

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().rawJson()).contains("第一句测试。");
        assertThat(rows.getFirst().rawJson()).contains("代词");
    }

    /** CSV 缺数据行：40001。 */
    @Test
    void rejectsCsvWithoutDataRows() {
        assertThatThrownBy(() -> parser.parse("CSV", "zh,ref,note"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.MALFORMED_REQUEST));
    }

    /** 超过 2000 行上限：40000。 */
    @Test
    void rejectsRowsOverLimit() {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index <= ImportRowParser.MAX_ROWS; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"zh\":\"第").append(index).append("句测试。\"}");
        }
        json.append(']');

        assertThatThrownBy(() -> parser.parse("JSON", json.toString()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
    }
}

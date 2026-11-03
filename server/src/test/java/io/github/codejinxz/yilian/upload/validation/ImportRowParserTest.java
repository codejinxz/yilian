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

    /** CSV：表头 + 数据行；blank/tags 单元格按 JSON 解析。 */
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

    /** [AC-3] CSV 引号内逗号：chunks JSON 不再被切列，完整解析为数组（016 P1·05#04）。 */
    @Test
    void parsesCsvQuotedChunkCellContainingCommas() {
        // chunks 单元格整体加引号、内部引号按 RFC4180 用 "" 转义；ref 同样含逗号也需引号包裹。
        String chunksCell =
                "\"[{\"\"index\"\":0,\"\"zh\"\":\"\"同\"\",\"\"answers\"\":[\"\"A, B and C\"\"],"
                        + "\"\"tag\"\":\"\"逗号\"\"}]\"";
        var rows =
                parser.parse(
                        "CSV",
                        "externalKey,zh,ref,note,chunks,blank,tags\n"
                                + "s_1,第一句测试。,\"A, B and C\",这是解析说明满足二十字的最低长度要求。,"
                                + chunksCell
                                + ",[0],[\"代词\"]");

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().externalKey()).isEqualTo("s_1");
        // chunks 单元格含逗号不再断列：整体按 JSON 解析成功（降级字符串会导致 CHUNKS_EMPTY）。
        assertThat(rows.getFirst().rawJson()).contains("\"answers\":[\"A, B and C\"]");
        assertThat(rows.getFirst().rawJson()).contains("\"tag\":\"逗号\"");
    }

    /** [AC-3] CSV 转义引号与引号内换行：按字面量保留在字段里（016 P1·05#04）。 */
    @Test
    void parsesCsvEscapedQuotesAndEmbeddedNewline() {
        var rows =
                parser.parse(
                        "CSV",
                        "externalKey,zh,ref,note,blank,tags\n"
                                + "s_1,第一句测试。,First sentence.,\"含\"\"引号\"\"与\n换行的说明满足二十字。\",[0],[\"代词\"]");

        assertThat(rows).hasSize(1);
        // rawJson 是 JSON 文本：引号与换行被 Jackson 转义，读回节点校验字段原值。
        var note = new ObjectMapper().readTree(rows.getFirst().rawJson()).path("note");
        assertThat(note.asText()).contains("含\"引号\"与\n换行的说明满足二十字。");
    }

    /** [AC-3] CSV 数据行列数与表头不一致：40001，拒绝静默错位（016 P1·05#04）。 */
    @Test
    void rejectsCsvColumnCountMismatch() {
        assertThatThrownBy(
                        () ->
                                parser.parse(
                                        "CSV", "externalKey,zh,ref,note,blank,tags\n" + "s_1,只有三列"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.MALFORMED_REQUEST));
    }

    /** [AC-3] CSV 未闭合引号：40001（016 P1·05#04）。 */
    @Test
    void rejectsCsvUnterminatedQuote() {
        assertThatThrownBy(
                        () ->
                                parser.parse(
                                        "CSV",
                                        "externalKey,zh,ref,note,blank,tags\n"
                                                + "s_1,第一句测试。,First sentence.,说明满足二十字。,[0],\"代词"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.MALFORMED_REQUEST));
    }

    /** [AC-3] UTF-8 BOM 剥离：Excel 导出文件首列表头不再带 \uFEFF（016 P2·05#07）。 */
    @Test
    void stripsUtf8BomBeforeParsing() {
        var rows =
                parser.parse(
                        "CSV",
                        "\uFEFFexternalKey,zh,ref,note,blank,tags\n"
                                + "s_1,第一句测试。,First test sentence here.,这是解析说明满足二十字的最低长度要求。,[0],[\"代词\"]");

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().externalKey()).isEqualTo("s_1");
    }

    /** [AC-3] JSON 输入带 BOM 同样剥离，不再 40001（016 P2·05#07）。 */
    @Test
    void stripsUtf8BomForJsonPayload() {
        var rows = parser.parse("JSON", "\uFEFF[{\"externalKey\":\"a1\",\"zh\":\"第一句测试。\"}]");

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().externalKey()).isEqualTo("a1");
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

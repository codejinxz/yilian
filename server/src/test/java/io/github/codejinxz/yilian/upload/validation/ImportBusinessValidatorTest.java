package io.github.codejinxz.yilian.upload.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.codejinxz.yilian.upload.dto.ImportRowError;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;

/**
 * 导入行业务校验器单测：字段级错误码逐项对齐 mock 契约 + tags 1–5。
 */
class ImportBusinessValidatorTest {

    private final ImportBusinessValidator validator = new ImportBusinessValidator();

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 合法行零错误（拼接 normalize 与 ref 一致）。 */
    @Test
    void validRowHasNoErrors() {
        var errors =
                validator.validate(
                        row(
                                "{\"externalKey\":\"a1\",\"zh\":\"他越来越重视这件事。\","
                                        + "\"ref\":\"He is paying more and more attention.\","
                                        + "\"note\":\"这是解析说明，长度满足最小要求，包含标点。\","
                                        + "\"chunks\":[{\"index\":0,\"zh\":\"他\",\"answers\":[\"He is paying more and more attention.\"],\"tag\":\"代词\"}],"
                                        + "\"blank\":[0],\"tags\":[\"代词\"]}"));

        assertThat(errors).isEmpty();
    }

    /** 长度类错误：ZH/REF/NOTE 三个码命中。 */
    @Test
    void lengthViolationsHitThreeCodes() {
        var errors = validator.validate(row("{\"zh\":\"短\",\"ref\":\"短句\",\"note\":\"太短\",\"chunks\":[],\"tags\":[]}"));

        assertThat(errors).extracting(ImportRowError::code)
                .contains("ZH_LENGTH_INVALID", "REF_LENGTH_INVALID", "NOTE_TOO_SHORT", "CHUNKS_EMPTY", "BLANK_EMPTY", "TAGS_COUNT_INVALID");
    }

    /** blank 下标不存在与 CHUNK_REF_MISMATCH。 */
    @Test
    void blankIndexAndRefMismatch() {
        var errors =
                validator.validate(
                        row(
                                "{\"zh\":\"他越来越重视这件事。\",\"ref\":\"He is paying more and more attention.\","
                                        + "\"note\":\"这是解析说明，长度满足最小要求，包含标点。\","
                                        + "\"chunks\":[{\"index\":0,\"zh\":\"他\",\"answers\":[\"He cares more and more.\"],\"tag\":\"代词\"}],"
                                        + "\"blank\":[5],\"tags\":[\"代词\"]}"));

        assertThat(errors).extracting(ImportRowError::code)
                .contains("BLANK_INDEX_INVALID", "CHUNK_REF_MISMATCH");
    }

    /** tags 超 5 个：TAGS_COUNT_INVALID（设计文档补充项，mock 未覆盖）。 */
    @Test
    void tagsOverFiveRejected() {
        var errors =
                validator.validate(
                        row(
                                "{\"zh\":\"他越来越重视这件事。\",\"ref\":\"He is paying more and more attention.\","
                                        + "\"note\":\"这是解析说明，长度满足最小要求，包含标点。\","
                                        + "\"chunks\":[{\"index\":0,\"zh\":\"他\",\"answers\":[\"He is paying more and more attention.\"],\"tag\":\"代词\"}],"
                                        + "\"blank\":[0],\"tags\":[\"一\",\"二\",\"三\",\"四\",\"五\",\"六\"]}"));

        assertThat(errors).extracting(ImportRowError::code).contains("TAGS_COUNT_INVALID");
    }

    /** 非对象行：ROW_NOT_OBJECT。 */
    @Test
    void nonObjectRowRejected() {
        var errors = validator.validate(objectMapper.readTree("[1,2]"));

        assertThat(errors).extracting(ImportRowError::code).contains("ROW_NOT_OBJECT");
    }

    /**
     * 构造行节点。
     *
     * @param json 行 JSON
     * @return 节点
     */
    private JsonNode row(String json) {
        return objectMapper.readTree(json);
    }
}

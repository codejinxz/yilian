package io.github.codejinxz.yilian.upload.validation;

import io.github.codejinxz.yilian.upload.dto.ImportRowError;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 导入行业务校验器：逐行产出 DATA-MODEL §6.7 结构的字段级错误。
 *
 * <p>校验口径与 mock 的 validateImportRow 一致（zh 4–60 字、ref 8–300、note 20–300、
 * chunks 非空且每块至少一个非空答案、blank 下标必须命中语块、chunks[].answers[0]
 * 按序拼接 normalize 后与 ref 一致），并补充设计文档要求的 tags 1–5 个。
 * 指纹去重（批内 + 题库）由服务层叠加为 DUPLICATE_SENTENCE，不在本器职责内。
 */
@Component
public final class ImportBusinessValidator {

    /** 无状态校验器，统一走单例。 */
    public ImportBusinessValidator() {}

    /**
     * 校验单行；错误非空即 INVALID。
     *
     * @param row 行 JSON 节点
     * @return 字段级错误列表；通过行为空列表
     */
    public List<ImportRowError> validate(JsonNode row) {
        List<ImportRowError> errors = new ArrayList<>();
        if (row == null || !row.isObject()) {
            errors.add(new ImportRowError("ROW_NOT_OBJECT", null, "行必须是对象"));
            return errors;
        }
        String zh = textOrEmpty(row, "zh");
        String ref = textOrEmpty(row, "ref");
        String note = textOrEmpty(row, "note");

        if (zh.codePoints().count() < 4 || zh.codePoints().count() > 60) {
            errors.add(new ImportRowError("ZH_LENGTH_INVALID", "zh", "中文长度必须为 4–60 字"));
        }
        if (ref.length() < 8 || ref.length() > 300) {
            errors.add(new ImportRowError("REF_LENGTH_INVALID", "ref", "参考译文长度必须为 8–300"));
        }
        if (note.codePoints().count() < 20 || note.codePoints().count() > 300) {
            errors.add(new ImportRowError("NOTE_TOO_SHORT", "note", "解析必须为 20–300 字"));
        }

        JsonNode chunks = row.path("chunks");
        if (!chunks.isArray() || chunks.isEmpty()) {
            errors.add(new ImportRowError("CHUNKS_EMPTY", "chunks", "chunks 不能为空"));
        }
        Set<Integer> chunkIndexes = new HashSet<>();
        List<String> canonicalParts = new ArrayList<>();
        if (chunks.isArray()) {
            int index = 0;
            for (JsonNode chunk : chunks) {
                String field = "chunks[" + index + "]";
                if (!chunk.isObject()) {
                    errors.add(new ImportRowError("CHUNK_INVALID", field, "语块必须是对象"));
                    index++;
                    continue;
                }
                chunkIndexes.add(chunk.path("index").isInt() ? chunk.path("index").asInt() : index);
                List<String> answers = new ArrayList<>();
                JsonNode answersNode = chunk.path("answers");
                if (answersNode.isArray()) {
                    for (JsonNode answer : answersNode) {
                        if (answer.isTextual() && !answer.asText().isBlank()) {
                            answers.add(answer.asText());
                        }
                    }
                }
                if (answers.isEmpty()) {
                    errors.add(
                            new ImportRowError(
                                    "CHUNK_ANSWER_EMPTY", field + ".answers", "每个语块至少一个非空答案"));
                } else {
                    canonicalParts.add(answers.getFirst());
                }
                if (!chunk.path("zh").isTextual() || chunk.path("zh").asText().isBlank()) {
                    errors.add(new ImportRowError("CHUNK_ZH_EMPTY", field + ".zh", "语块中文不能为空"));
                }
                index++;
            }
        }

        JsonNode blank = row.path("blank");
        if (!blank.isArray() || blank.isEmpty()) {
            errors.add(new ImportRowError("BLANK_EMPTY", "blank", "blank 至少一个下标"));
        } else {
            for (JsonNode value : blank) {
                if (!value.isInt() || !chunkIndexes.contains(value.asInt())) {
                    errors.add(
                            new ImportRowError(
                                    "BLANK_INDEX_INVALID", "blank", "blank 下标 " + value + " 不存在于 chunks"));
                }
            }
        }

        if (!canonicalParts.isEmpty()
                && !normalize(canonicalParts.stream().reduce((a, b) -> a + " " + b).orElse(""))
                        .equals(normalize(ref))) {
            errors.add(
                    new ImportRowError(
                            "CHUNK_REF_MISMATCH",
                            "chunks",
                            "chunks[].answers[0] 按序拼接与 ref 不一致"));
        }

        JsonNode tags = row.path("tags");
        if (!tags.isArray() || tags.isEmpty() || tags.size() > 5) {
            errors.add(new ImportRowError("TAGS_COUNT_INVALID", "tags", "考点标签必须为 1–5 个"));
        }
        return errors;
    }

    /**
     * 规范化文本用于拼接比对（与指纹/判分的 norm 口径一致：小写、折叠空白）。
     *
     * @param value 原文
     * @return 规范化结果
     */
    private String normalize(String value) {
        return value.strip().toLowerCase().replaceAll("\\s+", " ");
    }

    /**
     * 取文本字段；缺失或非文本返回空串。
     *
     * @param row 行节点
     * @param field 字段名
     * @return 文本值
     */
    private static String textOrEmpty(JsonNode row, String field) {
        JsonNode node = row.path(field);
        return node.isTextual() ? node.asText().trim() : "";
    }
}

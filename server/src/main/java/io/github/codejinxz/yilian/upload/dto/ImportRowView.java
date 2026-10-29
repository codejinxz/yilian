package io.github.codejinxz.yilian.upload.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 导入行视图（响应）；errors 为空表示通过。
 *
 * @param rowNo 数据行号，从 1 开始
 * @param externalKey 源 id，可空
 * @param status 行状态 VALID/INVALID/IMPORTED/SKIPPED
 * @param errors 逐行错误；通过行为空数组
 * @param sentenceId commit 成功后关联的题目 ULID，可空
 * @param sentenceVersionId commit 成功后关联的内容快照 id，可空
 */
@Schema(description = "导入行视图")
public record ImportRowView(
        @Schema(description = "数据行号") int rowNo,
        @Schema(description = "源 id") String externalKey,
        @Schema(description = "行状态") String status,
        @Schema(description = "逐行错误") List<ImportRowError> errors,
        @Schema(description = "关联题目 id") String sentenceId,
        @Schema(description = "关联快照 id") Long sentenceVersionId) {}

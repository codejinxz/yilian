package io.github.codejinxz.yilian.upload.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 导入行列表项（MyBatis 只读 DTO，映射 {@code import_batch_rows}）。
 *
 * @param rowNo 数据行号
 * @param externalKey 源 id
 * @param status 行状态
 * @param errorCodes 逐行错误 JSON 文本
 * @param sentenceId 关联题目 id
 * @param sentenceVersionId 关联快照 id
 * @param updatedAt 最后更新时间
 */
@Schema(description = "导入行列表项")
public record ImportBatchRowItem(
        @Schema(description = "数据行号") int rowNo,
        @Schema(description = "源 id") String externalKey,
        @Schema(description = "行状态") String status,
        @Schema(description = "逐行错误 JSON 文本") String errorCodes,
        @Schema(description = "关联题目 id") String sentenceId,
        @Schema(description = "关联快照 id") Long sentenceVersionId,
        @Schema(description = "最后更新时间") Instant updatedAt) {}

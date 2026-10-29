package io.github.codejinxz.yilian.upload.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 批次级错误摘要（DATA-MODEL §6.6 结构）；逐行原因在 {@code import_batch_rows.error_codes}。
 *
 * @param schemaVersion 结构版本
 * @param code 批次级错误码（如 ROW_VALIDATION_FAILED）
 * @param message 中文摘要
 * @param sampleRows 抽样行号（前 3 个失败行）
 */
@Schema(description = "批次级错误摘要")
public record ImportErrorSummary(
        @Schema(description = "结构版本") int schemaVersion,
        @Schema(description = "批次级错误码") String code,
        @Schema(description = "中文摘要") String message,
        @Schema(description = "抽样失败行号") List<Integer> sampleRows) {

    /** 结构版本常量。 */
    public static final int SCHEMA_VERSION = 1;
}

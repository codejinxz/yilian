package io.github.codejinxz.yilian.upload.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 预览响应：批次计数、行级明细与批次级摘要。
 *
 * @param batchId 批次 id
 * @param bankId 题库 id
 * @param status 恒为 PREVIEWED
 * @param format 文件格式
 * @param totalRows 数据行数
 * @param validRows 校验通过行数
 * @param invalidRows 校验失败行数
 * @param rows 行级明细（最多前 100 条）
 * @param errorSummary 批次级错误摘要，全通过为 null
 */
@Schema(description = "导入预览响应")
public record ImportPreviewResponse(
        @Schema(description = "批次 id") String batchId,
        @Schema(description = "题库 id") Long bankId,
        @Schema(description = "状态") String status,
        @Schema(description = "文件格式") String format,
        @Schema(description = "数据行数") long totalRows,
        @Schema(description = "校验通过行数") long validRows,
        @Schema(description = "校验失败行数") long invalidRows,
        @Schema(description = "行级明细") List<ImportRowView> rows,
        @Schema(description = "批次级错误摘要") ImportErrorSummary errorSummary) {}

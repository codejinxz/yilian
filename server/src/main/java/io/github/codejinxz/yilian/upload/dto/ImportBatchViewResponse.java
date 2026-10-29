package io.github.codejinxz.yilian.upload.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 导入批次视图（响应）。
 *
 * @param batchId 批次 id
 * @param bankId 题库 id
 * @param status 批次状态
 * @param format 文件格式 JSON/CSV
 * @param originalFilename 原始文件名
 * @param totalRows 数据行数
 * @param validRows 校验通过行数
 * @param invalidRows 校验失败行数
 * @param importedRows 已入库行数
 * @param createdAt 创建时间（ISO-8601 UTC）
 * @param previewedAt 预览完成时间
 * @param committedAt 提交完成时间
 * @param errorSummary 批次级错误摘要，无错误为 null
 */
@Schema(description = "导入批次视图")
public record ImportBatchViewResponse(
        @Schema(description = "批次 id") String batchId,
        @Schema(description = "题库 id") Long bankId,
        @Schema(description = "批次状态") String status,
        @Schema(description = "文件格式") String format,
        @Schema(description = "原始文件名") String originalFilename,
        @Schema(description = "数据行数") long totalRows,
        @Schema(description = "校验通过行数") long validRows,
        @Schema(description = "校验失败行数") long invalidRows,
        @Schema(description = "已入库行数") long importedRows,
        @Schema(description = "创建时间") String createdAt,
        @Schema(description = "预览完成时间") String previewedAt,
        @Schema(description = "提交完成时间") String committedAt,
        @Schema(description = "批次级错误摘要") ImportErrorSummary errorSummary) {}

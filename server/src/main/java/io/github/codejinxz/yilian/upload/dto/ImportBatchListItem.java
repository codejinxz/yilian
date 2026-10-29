package io.github.codejinxz.yilian.upload.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 批次列表行（MyBatis 只读 DTO，映射 {@code import_batches}）。
 *
 * @param id 批次 id
 * @param bankId 题库 id
 * @param format 文件格式
 * @param originalFilename 原始文件名
 * @param status 批次状态
 * @param totalRows 数据行数
 * @param validRows 校验通过行数
 * @param invalidRows 校验失败行数
 * @param importedRows 已入库行数
 * @param createdByName 创建人快照
 * @param createdAt 创建时间
 * @param previewedAt 预览完成时间
 * @param committedAt 提交完成时间
 * @param errorSummary 批次级错误摘要 JSON 文本，可空
 */
@Schema(description = "导入批次列表行")
public record ImportBatchListItem(
        @Schema(description = "批次 id") String id,
        @Schema(description = "题库 id") Long bankId,
        @Schema(description = "文件格式") String format,
        @Schema(description = "原始文件名") String originalFilename,
        @Schema(description = "批次状态") String status,
        @Schema(description = "数据行数") long totalRows,
        @Schema(description = "校验通过行数") long validRows,
        @Schema(description = "校验失败行数") long invalidRows,
        @Schema(description = "已入库行数") long importedRows,
        @Schema(description = "创建人快照") String createdByName,
        @Schema(description = "创建时间") Instant createdAt,
        @Schema(description = "预览完成时间") Instant previewedAt,
        @Schema(description = "提交完成时间") Instant committedAt,
        @Schema(description = "错误摘要 JSON 文本") String errorSummary) {}

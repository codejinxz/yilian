package io.github.codejinxz.yilian.upload.dto;

import io.github.codejinxz.yilian.common.api.PageResponse;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 批次详情：批次视图 + 行分页。
 *
 * @param batch 批次视图
 * @param rows 行分页
 */
@Schema(description = "导入批次详情")
public record ImportBatchDetailResponse(
        @Schema(description = "批次视图") ImportBatchViewResponse batch,
        @Schema(description = "行分页") PageResponse<ImportRowView> rows) {}

package io.github.codejinxz.yilian.upload.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Size;

/**
 * 提交导入批次请求体。
 *
 * @param reason 可选审计原因
 */
@Schema(description = "提交导入批次请求")
public record CommitImportRequest(
        @Schema(description = "可选审计原因") @Size(max = 500, message = "原因最多 500 个字符") String reason) {}

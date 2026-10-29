package io.github.codejinxz.yilian.upload.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 回滚导入批次请求体。
 *
 * @param reason 回滚原因（审计必填）
 */
@Schema(description = "回滚导入批次请求")
public record RollbackImportRequest(
        @Schema(description = "回滚原因")
                @NotBlank(message = "请填写回滚原因")
                @Size(max = 500, message = "原因最多 500 个字符")
                String reason) {}

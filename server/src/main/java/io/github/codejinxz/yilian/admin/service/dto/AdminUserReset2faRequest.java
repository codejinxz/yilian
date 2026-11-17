package io.github.codejinxz.yilian.admin.service.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 管理端重置用户两步验证请求体（API §8.2d）。
 *
 * <p>重置是敏感动作，原因必填并随 {@code @OperationLog} 落审计；请求体不带任何密钥或 恢复码字段——清空后用户需重新走 setup/activate 注册。
 *
 * @param reason 审计原因，必填 1–255 字符
 */
@Schema(description = "管理端重置两步验证请求体；reason 必填。")
public record AdminUserReset2faRequest(
        @Schema(description = "审计原因", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank(message = "请填写原因")
                @Size(max = 255, message = "原因最多 255 字符")
                String reason) {}

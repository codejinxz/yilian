package io.github.codejinxz.yilian.admin.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 管理端删除账号请求体（API §8.2c，018 AC-6b）。
 *
 * <p>删除是软删（30 天冷静期），原因必填并随 {@code @OperationLog} 落审计；前端已按 {reason} 传体。
 *
 * @param reason 审计原因，必填 1–255 字符
 */
public record AdminUserDeleteRequest(
        @NotBlank(message = "请填写原因") @Size(max = 255, message = "原因最多 255 字符") String reason) {}

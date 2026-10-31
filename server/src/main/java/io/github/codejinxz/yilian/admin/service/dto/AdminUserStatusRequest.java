package io.github.codejinxz.yilian.admin.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 管理端启停账号请求体（API §8.1）。
 *
 * @param enabled true 恢复、false 停用
 * @param reason 审计原因，必填 1–255 字符
 */
public record AdminUserStatusRequest(
        @NotNull(message = "enabled 必填") Boolean enabled,
        @NotBlank(message = "请填写原因") @Size(max = 255, message = "原因最多 255 字符") String reason) {}

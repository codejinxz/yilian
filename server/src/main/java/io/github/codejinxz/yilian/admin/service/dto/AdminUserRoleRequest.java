package io.github.codejinxz.yilian.admin.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 管理端变更角色请求体（API §8.1）。
 *
 * <p>role 用字符串绑定并在 service 侧解析；管理模块不 import system 的 {@code entity} 包。
 *
 * @param role 目标角色 USER/ADMIN
 * @param reason 审计原因，必填 1–255 字符
 */
public record AdminUserRoleRequest(
        @NotBlank(message = "role 必填") @Pattern(regexp = "USER|ADMIN", message = "role 不受支持")
                String role,
        @NotBlank(message = "请填写原因") @Size(max = 255, message = "原因最多 255 字符") String reason) {}

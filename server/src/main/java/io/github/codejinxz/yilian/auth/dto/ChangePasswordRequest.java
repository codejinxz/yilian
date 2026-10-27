package io.github.codejinxz.yilian.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 改密请求体，对应 API §4.7。
 *
 * @param currentPassword 当前密码
 * @param newPassword 新密码，8–72 字节
 */
@Schema(description = "改密请求：成功后吊销该用户全部 refresh 会话。")
public record ChangePasswordRequest(
        @Schema(description = "当前密码", accessMode = Schema.AccessMode.WRITE_ONLY)
                @NotBlank(message = "请输入当前密码")
                @Size(max = 72, message = "当前密码最多 72 个字符")
                String currentPassword,
        @Schema(description = "新密码，8–72 字节", accessMode = Schema.AccessMode.WRITE_ONLY)
                @NotBlank(message = "请输入新密码")
                @Size(max = 72, message = "新密码最多 72 个字符")
                String newPassword) {}

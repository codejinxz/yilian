package io.github.codejinxz.yilian.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 注册请求体，对应 API §4.6。
 *
 * @param email 邮箱，必填且唯一
 * @param username 登录名，可空；为空时由邮箱本地部分派生
 * @param password 明文密码，8–72 字节
 * @param inviteCode 邀请码，开启邀请制时必填
 */
@Schema(description = "注册请求：邀请码是否必填由 yilian.registration.invite-required 决定。")
public record RegisterRequest(
        @Schema(description = "邮箱，唯一", example = "learner@example.com")
                @NotBlank(message = "请输入邮箱")
                @Email(message = "邮箱格式不正确")
                @Size(max = 255, message = "邮箱最多 255 个字符")
                String email,
        @Schema(description = "登录名，3–64 位字母、数字、点、下划线或连字符；为空时由邮箱派生", example = "learner")
                @Size(max = 64, message = "用户名最多 64 个字符")
                String username,
        @Schema(description = "明文密码，8–72 字节", accessMode = Schema.AccessMode.WRITE_ONLY)
                @NotBlank(message = "请输入密码")
                @Size(max = 72, message = "密码最多 72 个字符")
                String password,
        @Schema(description = "邀请码明文；只用于比对哈希，不写日志", accessMode = Schema.AccessMode.WRITE_ONLY)
                @Size(max = 64, message = "邀请码最多 64 个字符")
                String inviteCode) {}

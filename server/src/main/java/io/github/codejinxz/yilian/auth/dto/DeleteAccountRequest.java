package io.github.codejinxz.yilian.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 自助删号请求体：密码确认（必填），开启 2FA 的用户还需当前动态码或恢复码。
 *
 * @param password 当前登录密码（BCrypt 比对）
 * @param totpCode 验证器当前 8 位动态码或剩余恢复码之一；未开启 2FA 时可缺省
 */
@Schema(description = "自助删号请求：密码确认；开启两步验证的用户必须附当前动态码或恢复码。")
public record DeleteAccountRequest(
        @Schema(
                        description = "当前登录密码",
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        accessMode = Schema.AccessMode.WRITE_ONLY)
                @NotBlank(message = "请输入当前密码")
                @Size(max = 72, message = "密码最多 72 个字符")
                String password,
        @Schema(
                        description = "验证器当前 8 位动态码或恢复码；未开启两步验证时可缺省",
                        accessMode = Schema.AccessMode.WRITE_ONLY)
                @Pattern(regexp = "\\d{8}", message = "验证码为 8 位数字")
                String totpCode) {}

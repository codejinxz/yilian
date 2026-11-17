package io.github.codejinxz.yilian.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 两步验证关闭请求体：密码 + 当前动态码双确认（防止仅凭被劫持的会话关闭 2FA）。
 *
 * @param password 当前登录密码
 * @param code 验证器当前 8 位动态码（或剩余恢复码之一）
 */
@Schema(description = "两步验证关闭请求体；密码与当前动态码双确认。")
public record TotpDisableRequest(
        @Schema(description = "当前登录密码", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank(message = "请输入当前密码")
                String password,
        @Schema(description = "验证器当前 8 位动态码或恢复码", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank(message = "请输入 8 位验证码")
                @Pattern(regexp = "\\d{8}", message = "验证码为 8 位数字")
                String code) {}

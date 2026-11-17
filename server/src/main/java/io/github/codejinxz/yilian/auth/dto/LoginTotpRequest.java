package io.github.codejinxz.yilian.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 登录第二步请求体（POST /api/auth/login/totp）。
 *
 * @param challengeToken 40305 的 data.challengeToken 原样回传
 * @param code 8 位动态码（TOTP 或恢复码，同一字段不区分来源，服务端先验 TOTP 再验恢复码）
 */
@Schema(description = "登录两步验证请求体；code 同时接受 TOTP 动态码与一次性恢复码。")
public record LoginTotpRequest(
        @Schema(description = "40305 返回的挑战 token", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank(message = "登录挑战缺失，请重新登录")
                String challengeToken,
        @Schema(description = "8 位动态码或恢复码", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank(message = "请输入 8 位验证码")
                @Pattern(regexp = "\\d{8}", message = "验证码为 8 位数字")
                String code) {}

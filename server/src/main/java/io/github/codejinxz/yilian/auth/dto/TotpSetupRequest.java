package io.github.codejinxz.yilian.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;

/**
 * 两步验证第一步（setup）请求体：密码确认防会话被劫持后静默开启 2FA。
 *
 * @param password 当前登录密码
 */
@Schema(description = "两步验证注册请求体；需要当前密码确认。")
public record TotpSetupRequest(
        @Schema(description = "当前登录密码", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank(message = "请输入当前密码")
                String password) {}

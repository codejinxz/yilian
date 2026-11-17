package io.github.codejinxz.yilian.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 两步验证第二步（activate）请求体：验证器出示当前动态码，通过才启用。
 *
 * @param code 验证器当前 8 位动态码
 */
@Schema(description = "两步验证激活请求体；code 为验证器当前动态码。")
public record TotpActivateRequest(
        @Schema(description = "验证器当前 8 位动态码", requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank(message = "请输入 8 位验证码")
                @Pattern(regexp = "\\d{8}", message = "验证码为 8 位数字")
                String code) {}

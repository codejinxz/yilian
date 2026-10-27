package io.github.codejinxz.yilian.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 一次签发的 access token。
 *
 * @param token JWT 字符串
 * @param tokenType 固定为 Bearer
 * @param expiresIn 有效期秒数
 */
@Schema(description = "一次签发的 access token；仅在服务内部转换为 AuthResponse 后对外暴露。")
public record AccessToken(
        @Schema(
                        description = "JWT 字符串",
                        example = "<access-token>",
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        accessMode = Schema.AccessMode.READ_ONLY)
                String token,
        @Schema(
                        description = "令牌类型，固定为 Bearer",
                        example = "Bearer",
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        accessMode = Schema.AccessMode.READ_ONLY)
                String tokenType,
        @Schema(
                        description = "有效期秒数",
                        example = "900",
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        accessMode = Schema.AccessMode.READ_ONLY)
                long expiresIn) {}

package io.github.codejinxz.yilian.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 登录或刷新成功后的认证结果。
 *
 * <p>refresh token 只放在 HttpOnly Cookie 中，绝不进入响应体。
 *
 * @param accessToken JWT access token
 * @param tokenType 固定为 Bearer
 * @param expiresIn access token 有效期秒数
 * @param user 当前用户信息
 */
@Schema(description = "登录或刷新成功后的认证结果；refresh token 只存在 HttpOnly Cookie 中，绝不进入响应体。")
public record AuthResponse(
        @Schema(
                        description = "JWT access token；前端只存内存，请求时放进 Authorization: Bearer 头",
                        example = "<access-token>",
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        accessMode = Schema.AccessMode.READ_ONLY)
                String accessToken,
        @Schema(
                        description = "令牌类型，固定为 Bearer",
                        example = "Bearer",
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        accessMode = Schema.AccessMode.READ_ONLY)
                String tokenType,
        @Schema(
                        description = "access token 有效期，单位秒",
                        example = "900",
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        accessMode = Schema.AccessMode.READ_ONLY)
                long expiresIn,
        @Schema(
                        description = "当前登录用户",
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        accessMode = Schema.AccessMode.READ_ONLY)
                CurrentUserResponse user) {

    /**
     * 把签发结果与用户视图拼成响应，避免 Controller 逐个字段复制。
     *
     * @param token 本次签发的 access token
     * @param user 当前用户视图
     * @return 认证响应
     */
    public static AuthResponse from(AccessToken token, CurrentUserResponse user) {
        return new AuthResponse(token.token(), token.tokenType(), token.expiresIn(), user);
    }
}

package io.github.codejinxz.yilian.auth.vo;

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
 * @param remainingRecoveryCodes 本次登录若消费了恢复码，剩余可用恢复码数；普通登录/刷新为 null—— 只有恢复码登录需要提醒「恢复码又少了一枚」
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
                CurrentUserResponse user,
        @Schema(
                        description = "恢复码登录后剩余可用恢复码数；普通登录/刷新为 null",
                        example = "9",
                        accessMode = Schema.AccessMode.READ_ONLY)
                Long remainingRecoveryCodes) {

    /**
     * 把签发结果与用户视图拼成响应（无恢复码消耗），避免 Controller 逐个字段复制。
     *
     * @param token 本次签发的 access token
     * @param user 当前用户视图
     * @return 认证响应
     */
    public static AuthResponse from(AccessToken token, CurrentUserResponse user) {
        return from(token, user, null);
    }

    /**
     * 把签发结果与用户视图拼成响应，恢复码登录时携带剩余恢复码数。
     *
     * @param token 本次签发的 access token
     * @param user 当前用户视图
     * @param remainingRecoveryCodes 剩余恢复码数；非恢复码登录传 null
     * @return 认证响应
     */
    public static AuthResponse from(
            AccessToken token, CurrentUserResponse user, Long remainingRecoveryCodes) {
        return new AuthResponse(
                token.token(), token.tokenType(), token.expiresIn(), user, remainingRecoveryCodes);
    }
}

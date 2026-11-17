package io.github.codejinxz.yilian.auth.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 40305「需要两步验证」失败信封的 data 载荷：challengeToken 只代表「密码已验证、等待 动态码」，不是 access token，过滤器链拒收；5 分钟过期。
 *
 * @param challengeToken 登录第二步挑战 token（POST /api/auth/login/totp 时回传）
 * @param expiresIn 有效期秒数（固定 300，前端据此提示倒计时）
 */
@Schema(description = "登录两步验证挑战载荷（40305 的 data）；challengeToken 不是 access token。")
public record TotpChallengeView(
        @Schema(
                        description = "两步验证挑战 token，5 分钟有效；第二步验证时原样回传",
                        example = "<challenge-token>",
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        accessMode = Schema.AccessMode.READ_ONLY)
                String challengeToken,
        @Schema(
                        description = "挑战有效期秒数",
                        example = "300",
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        accessMode = Schema.AccessMode.READ_ONLY)
                long expiresIn) {}

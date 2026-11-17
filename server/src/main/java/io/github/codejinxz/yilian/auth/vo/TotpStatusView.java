package io.github.codejinxz.yilian.auth.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 当前用户两步验证状态（GET /api/auth/2fa/status）。
 *
 * @param enabled 是否已启用两步验证
 * @param enrolledAt 启用时刻；未启用为 null
 * @param remainingRecoveryCodes 剩余可用恢复码数；未启用时为 0
 */
@Schema(description = "当前用户两步验证状态；响应不含任何密钥材料。")
public record TotpStatusView(
        @Schema(
                        description = "是否已启用两步验证",
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        accessMode = Schema.AccessMode.READ_ONLY)
                boolean enabled,
        @Schema(description = "启用时刻（UTC）；未启用为 null", accessMode = Schema.AccessMode.READ_ONLY)
                Instant enrolledAt,
        @Schema(
                        description = "剩余可用恢复码数",
                        example = "10",
                        requiredMode = Schema.RequiredMode.REQUIRED,
                        accessMode = Schema.AccessMode.READ_ONLY)
                int remainingRecoveryCodes) {}

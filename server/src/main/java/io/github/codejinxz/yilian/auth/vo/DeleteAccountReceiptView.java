package io.github.codejinxz.yilian.auth.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 自助删号回执：软删完成时刻与匿名化时点。
 *
 * @param deletedAtUtc 软删完成时刻（UTC）；30 天冷静期自该时刻起算
 * @param anonymizeAtUtc 匿名化时点（UTC）= deletedAtUtc + 30 天（冷静期天数走配置键， 与匿名化日调度同源）
 */
@Schema(description = "自助删号回执：账号已软删，全部会话已吊销；匿名化时点为 30 天冷静期到期。")
public record DeleteAccountReceiptView(
        @Schema(description = "软删完成时刻（UTC）") String deletedAtUtc,
        @Schema(description = "匿名化时点（UTC）= deletedAtUtc + 30 天") String anonymizeAtUtc) {}

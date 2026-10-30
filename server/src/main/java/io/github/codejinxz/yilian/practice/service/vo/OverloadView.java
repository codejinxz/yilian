package io.github.codejinxz.yilian.practice.service.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 积压降载视图（GAP P0「积压熔断」，R-07 推后 V2 的最小服务端口径）。
 *
 * <p>到期数超过阈值进入降载：取题只推 {@code pushLimit} 道，其余可经
 * POST /api/practice/reviews/postpone 顺延。阈值与推送上限本期为常量，不落配置。
 *
 * @param dueCount 当前到期数
 * @param threshold 降载阈值
 * @param pushLimit 降载时一次推送上限
 * @param overload 是否处于降载
 */
@Schema(description = "积压降载状态：overload=true 时客户端只推 pushLimit 道")
public record OverloadView(
        @Schema(description = "当前到期数") long dueCount,
        @Schema(description = "降载阈值") int threshold,
        @Schema(description = "推送上限") int pushLimit,
        @Schema(description = "是否降载") boolean overload) {}

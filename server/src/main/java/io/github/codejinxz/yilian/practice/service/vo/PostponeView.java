package io.github.codejinxz.yilian.practice.service.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 顺延结果：把降载推送范围外的到期卡整体顺延 3 天。
 *
 * <p>顺延不写 review_logs（非 FSRS 排程事件），只改 reviews.due_at；用户动作，非自动化。 「顺延有上限，超限转重学」依赖顺延计数字段，暂未实现， 当前不做上限。
 *
 * @param postponed 实际顺延张数
 * @param kept 保留在今日队列的张数（推送上限内）
 */
@Schema(description = "顺延结果：postponed 为推迟张数，kept 为保留张数")
public record PostponeView(
        @Schema(description = "顺延张数") long postponed, @Schema(description = "保留张数") long kept) {}

package io.github.codejinxz.yilian.practice.service.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 复习卡列表/重置视图（API §6.7）。
 *
 * @param id 卡 id
 * @param sentenceId 题目 ULID
 * @param mode 最近练习模式
 * @param state 三档 UI 状态
 * @param fsrsState FSRS 四态
 * @param dueAt 到期时间
 * @param stability 稳定度
 * @param difficulty 难度
 * @param reps 复习次数
 * @param lapses 遗忘次数
 * @param scheduledDays 排程天数
 * @param lastRating 最近四档评分
 * @param lastReviewedAt 最近复习时间
 */
@Schema(description = "复习卡视图：state 是三档投影，fsrsState 是 FSRS 四态")
public record ReviewCardView(
        @Schema(description = "卡 id") Long id,
        @Schema(description = "题目 id") String sentenceId,
        @Schema(description = "最近练习模式") String mode,
        @Schema(description = "三档状态") String state,
        @Schema(description = "FSRS 状态") String fsrsState,
        @Schema(description = "到期时间") Instant dueAt,
        @Schema(description = "稳定度") double stability,
        @Schema(description = "难度") double difficulty,
        @Schema(description = "复习次数") int reps,
        @Schema(description = "遗忘次数") int lapses,
        @Schema(description = "排程天数") int scheduledDays,
        @Schema(description = "最近评分 1–4，可空") Integer lastRating,
        @Schema(description = "最近复习时间，可空") Instant lastReviewedAt) {}

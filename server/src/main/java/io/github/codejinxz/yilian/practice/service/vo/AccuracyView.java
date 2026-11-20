package io.github.codejinxz.yilian.practice.service.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * 正确率统计（API §7.1 + daily14/recent 扩展）。
 *
 * @param from 统计起始（用户本地日期）
 * @param to 统计截止（用户本地日期）
 * @param totalAttempts 区间内提交数（含 RETRY/REVEAL）
 * @param correctRate 全对率（以有分数判分为分母，0–100；无判分时为 null）
 * @param averageScore 平均分（有分数判分，0–100；无判分时为 null）
 * @param byMode 分模式统计
 * @param daily14 最近 14 天逐日统计（含 from/to 区间外补零的完整 14 天）
 * @param recent 最近提交（时间倒序，最多 10 条）
 */
@Schema(description = "正确率统计：口径为「最新判分的有分数记录」")
public record AccuracyView(
        @Schema(description = "起始日期") LocalDate from,
        @Schema(description = "截止日期") LocalDate to,
        @Schema(description = "提交总数") long totalAttempts,
        @Schema(description = "全对率，无判分时为 null") Double correctRate,
        @Schema(description = "平均分，无判分时为 null") Double averageScore,
        @Schema(description = "分模式统计") List<ModeAccuracyView> byMode,
        @Schema(description = "最近 14 天逐日") List<DailyAccuracyView> daily14,
        @Schema(description = "最近提交") List<RecentAttemptView> recent) {

    /**
     * 分模式统计。
     *
     * @param mode 模式
     * @param attempts 提交数
     * @param correctRate 全对率
     */
    @Schema(description = "分模式统计")
    public record ModeAccuracyView(
            @Schema(description = "模式") String mode,
            @Schema(description = "提交数") long attempts,
            @Schema(description = "全对率") Double correctRate) {}

    /**
     * 逐日统计。
     *
     * @param date 用户本地日期
     * @param attempts 提交数
     * @param correctRate 全对率
     */
    @Schema(description = "逐日统计")
    public record DailyAccuracyView(
            @Schema(description = "本地日期") LocalDate date,
            @Schema(description = "提交数") long attempts,
            @Schema(description = "全对率，无判分时为 null") Double correctRate) {}

    /**
     * 最近提交投影。
     *
     * @param attemptId 提交 id
     * @param sentenceId 题目 id
     * @param mode 模式
     * @param kind 提交类型
     * @param score 最新判分分数
     * @param createdAt 提交时间
     */
    @Schema(description = "最近提交")
    public record RecentAttemptView(
            @Schema(description = "提交 id") String attemptId,
            @Schema(description = "题目 id") String sentenceId,
            @Schema(description = "模式") String mode,
            @Schema(description = "提交类型") String kind,
            @Schema(description = "分数，REVEAL 为 null") Integer score,
            @Schema(description = "提交时间") java.time.Instant createdAt) {}
}

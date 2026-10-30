package io.github.codejinxz.yilian.practice.service.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * 今日启动台数字（API §6.9）：一屏一句，不做仪表盘。
 *
 * @param date 用户本地日期
 * @param timezone 用户 IANA 时区
 * @param dueCount 到期复习数
 * @param doneCount 今日已完成提交数
 * @param goal 每日目标
 * @param correctRate 今日全对率（0–100；无判分提交时为 null）
 * @param streakDays 连续练习天数（以用户本地日界计）
 */
@Schema(description = "今日概览：本地日界口径")
public record TodayView(
        @Schema(description = "用户本地日期") LocalDate date,
        @Schema(description = "用户时区") String timezone,
        @Schema(description = "到期数") long dueCount,
        @Schema(description = "今日完成数") long doneCount,
        @Schema(description = "每日目标") int goal,
        @Schema(description = "今日全对率，无判分提交时为 null") Integer correctRate,
        @Schema(description = "连续练习天数") int streakDays) {}

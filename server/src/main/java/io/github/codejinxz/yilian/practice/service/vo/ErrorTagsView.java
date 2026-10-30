package io.github.codejinxz.yilian.practice.service.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

import java.util.List;

/**
 * 考点错误分布（API §7.2）：来自判分批注的 tag 聚合，已 DISPUTE 的批注不计。
 *
 * @param from 统计起始（用户本地日期）
 * @param to 统计截止（用户本地日期）
 * @param items 考点计数（按次数降序）
 */
@Schema(description = "考点错误分布：dispute 批注不计入")
public record ErrorTagsView(
        @Schema(description = "起始日期") LocalDate from,
        @Schema(description = "截止日期") LocalDate to,
        @Schema(description = "考点列表") List<TagCountView> items) {

    /**
     * 单个考点计数。
     *
     * @param tag 考点标签
     * @param count 错误批注数
     * @param ratio 占比 0–100，保留一位小数
     */
    @Schema(description = "考点计数")
    public record TagCountView(
            @Schema(description = "考点标签") String tag,
            @Schema(description = "错误数") long count,
            @Schema(description = "占比") Double ratio) {}
}

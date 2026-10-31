package io.github.codejinxz.yilian.admin.service.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 批量变更题目状态结果（API §8.8）。
 *
 * @param updated 实际变更的题目数（不存在的 id 与已处于目标状态的 id 不计入）
 */
@Schema(description = "批量状态变更结果：updated 是实际变更数，不存在的 id 与无变化的 id 不计入。")
public record AdminSentenceBulkStatusResult(@Schema(description = "实际变更的题目数") int updated) {}

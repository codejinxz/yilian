package io.github.codejinxz.yilian.admin.service.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 批量移动题目结果（API §8.8）。
 *
 * @param moved 实际移动的题目数（不存在的 id 与已在目标题库的 id 不计入）
 */
@Schema(description = "批量移动结果：moved 是实际移动数，不存在的 id 与已在目标题库的 id 不计入。")
public record AdminSentenceBulkMoveResult(@Schema(description = "实际移动的题目数") int moved) {}

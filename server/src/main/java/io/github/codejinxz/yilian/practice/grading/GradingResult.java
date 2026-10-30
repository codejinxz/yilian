package io.github.codejinxz.yilian.practice.grading;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 判分结果，结构与前端 {@code gradingResultSchema} 逐字段一致（API §6.2 / DATA-MODEL §6.4）。
 *
 * <p>{@code score} 在 REVEAL（看答案）时为 null；其余场景 0–100。 该对象以 JSON 原文落库到 {@code
 * attempt_gradings.result}，字段增删必须升 schemaVersion。
 *
 * @param schemaVersion 结果结构版本，当前恒为 1
 * @param score 得分；REVEAL 为 null
 * @param maxScore 满分，恒为 100
 * @param hitCount 命中词数
 * @param totalCount 应得词总数
 * @param annotations 逐槽批注
 * @param summary 一句话摘要，如「还差 2 处」
 */
@Schema(description = "判分结果：score 为 null 表示看答案未计分")
public record GradingResult(
        @Schema(description = "结构版本，恒为 1") int schemaVersion,
        @Schema(description = "得分 0–100，REVEAL 为 null") Integer score,
        @Schema(description = "满分") int maxScore,
        @Schema(description = "命中词数") int hitCount,
        @Schema(description = "应得词总数") int totalCount,
        @Schema(description = "逐槽批注") List<GradingAnnotation> annotations,
        @Schema(description = "一句话摘要") String summary) {}

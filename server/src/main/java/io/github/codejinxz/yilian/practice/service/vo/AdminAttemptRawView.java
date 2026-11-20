package io.github.codejinxz.yilian.practice.service.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import tools.jackson.databind.JsonNode;

/**
 * 管理端查看答题原文视图（API §8.7）：一次查看的完整回执。
 *
 * <p>answer 与 grading.result 原样回放库里存的 JSON 文档，不做投影裁剪； 除这两个文档外不携带任何用户身份材料（无密码/无 token），审计行 id 随视图返回，
 * 让前端可以把「看原文」与审计痕迹一一对应。
 *
 * @param attemptId 答题提交 id
 * @param userId 答题用户 id（字符串化，与前端 idSchema 解析契约一致）
 * @param sentenceId 题目 ULID
 * @param answer 用户作答原文 JSON
 * @param grading 最新判分（sequence 最大行）；该提交尚无判分时为 null
 * @param auditId 本次查看落下的审计行 id（字符串化）
 */
@Schema(description = "管理端查看答题原文回执")
public record AdminAttemptRawView(
        @Schema(description = "答题提交 id") String attemptId,
        @Schema(description = "答题用户 id") String userId,
        @Schema(description = "题目 ULID") String sentenceId,
        @Schema(description = "作答原文 JSON") JsonNode answer,
        @Schema(description = "最新判分；尚无判分为 null") Grading grading,
        @Schema(description = "审计行 id") String auditId) {

    /**
     * 最新判分摘要（API §8.7 示例字段：id/score/result）。
     *
     * @param id 判分行 id（字符串化）
     * @param score 分数；REVEAL 等无分为 null
     * @param result 判分结果 JSON
     */
    @Schema(description = "最新判分摘要")
    public record Grading(
            @Schema(description = "判分行 id") String id,
            @Schema(description = "分数，可空") Integer score,
            @Schema(description = "判分结果 JSON") JsonNode result) {}
}

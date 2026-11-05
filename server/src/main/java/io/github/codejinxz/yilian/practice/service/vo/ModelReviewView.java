package io.github.codejinxz.yilian.practice.service.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 模型复核结果（API §6.13）。
 *
 * @param score 复核分（0–100，已钳制）
 * @param reason 模型复核理由（中文，≤200 字）
 * @param modelSnapshot 模型快照（providerId:model），与 attempt_gradings.model_snapshot 同源
 * @param latencyMs 模型调用耗时毫秒（含重试的整体墙钟）
 * @param createdAt 复核记录创建时间（UTC）
 * @param sequence 本条复核在 attempt_gradings 里的序号（首判 sequence+1）
 * @param originalScore 首判分（供前端对比展示）
 */
@Schema(description = "模型复核结果：追加判分记录，不覆盖首判")
public record ModelReviewView(
        @Schema(description = "复核分 0–100") int score,
        @Schema(description = "复核理由") String reason,
        @Schema(description = "模型快照 providerId:model") String modelSnapshot,
        @Schema(description = "模型调用耗时毫秒") Long latencyMs,
        @Schema(description = "复核时间") Instant createdAt,
        @Schema(description = "判分序号（首判+1）") int sequence,
        @Schema(description = "首判分") int originalScore) {}

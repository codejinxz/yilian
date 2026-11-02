package io.github.codejinxz.yilian.practice.service.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * FSRS 参数版本视图（API §8.x admin/fsrs）：weights 解码为数值数组，metrics 解码为对象。
 *
 * @param id 版本行 id
 * @param algorithm 算法标识（FSRS-6）
 * @param ownerType 归属（GLOBAL/USER）
 * @param status CANDIDATE/ACTIVE/RETIRED
 * @param weights 21 维权重（DATA-MODEL §6.8）
 * @param sampleCount 训练样本数（种子行为 0）
 * @param metrics 训练指标（DATA-MODEL §6.9），种子行为 null
 * @param optimizedAt 优化完成时间，种子行为 null
 * @param createdAt 创建时间
 * @param reason 落库理由，可空
 */
@Schema(description = "FSRS 参数版本：权重与训练摘要")
public record FsrsParameterVersionView(
        @Schema(description = "版本 id") Long id,
        @Schema(description = "算法标识") String algorithm,
        @Schema(description = "归属类型") String ownerType,
        @Schema(description = "状态") String status,
        @Schema(description = "21 维权重") List<Double> weights,
        @Schema(description = "训练样本数") int sampleCount,
        @Schema(description = "训练指标") Map<String, Object> metrics,
        @Schema(description = "优化完成时间") Instant optimizedAt,
        @Schema(description = "创建时间") Instant createdAt,
        @Schema(description = "落库理由") String reason) {}

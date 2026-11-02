package io.github.codejinxz.yilian.practice.service.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;

/**
 * 触发 FSRS 优化的请求体。
 *
 * <p>单人产品语境：优化谁的日志必须显式指定（管理员 ≠ 学习者，不做「默认优化自己」的隐式绑定）； 产出的候选权重是 GLOBAL 行，启用后对所有用户生效。
 *
 * @param userId 目标用户的 id（其 review_logs 作为训练集）
 */
@Schema(description = "FSRS 优化触发请求：指定训练数据所属用户")
public record FsrsOptimizeRequest(
        @Schema(description = "目标用户 id（review_logs 训练集归属）") @NotNull Long userId) {}

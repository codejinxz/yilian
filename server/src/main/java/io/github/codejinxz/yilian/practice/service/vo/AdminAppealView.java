package io.github.codejinxz.yilian.practice.service.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 管理端申诉列表/详情行（API §8.16）：终审需要的全部上下文——被申诉批注、用户主张、 AI 意见与题目摘要。
 *
 * <p>题目摘要（sentenceZh）与申诉人（username）由查询 JOIN 补齐；题目已删除时摘要为 null， 列表行仍可见（申诉不脱离提交）。decisionVerdict 从
 * decision JSON 抽取 ({@code $.verdict})， 为 UNAVAILABLE 或 null 时表示无有效 AI 意见。
 *
 * @param id 申诉行 id
 * @param attemptId 被申诉提交 id
 * @param annotationKey 批注键（slot:N / free:0）
 * @param sentenceId 题目 ULID
 * @param chunkIndex 语块下标
 * @param kind 类型 APPEAL / DISPUTE
 * @param status 状态 PENDING / ACCEPTED / REJECTED
 * @param proposedAnswer 用户认为可接受的答案
 * @param reason 用户申诉理由
 * @param decisionVerdict AI 初裁结论（decision JSON 的 verdict）；未初裁为 null
 * @param decisionReason 可展示理由：初裁成功=AI 理由，终审后=终审理由
 * @param decidedBy 裁决者 LLM / ADMIN
 * @param decidedAt 裁决时间
 * @param modelSnapshot 初裁模型快照
 * @param promptVersionId 初裁 prompt 版本 id
 * @param createdAt 申诉时间
 * @param sentenceZh 题目中文原句（JOIN 补齐；题目已删为 null）
 * @param username 申诉人用户名（JOIN 补齐）
 */
@Schema(description = "管理端申诉行：终审上下文含 AI 意见、题目摘要与用户主张")
public record AdminAppealView(
        @Schema(description = "申诉行 id") long id,
        @Schema(description = "被申诉提交 id") String attemptId,
        @Schema(description = "批注键") String annotationKey,
        @Schema(description = "题目 ULID") String sentenceId,
        @Schema(description = "语块下标") int chunkIndex,
        @Schema(description = "APPEAL / DISPUTE") String kind,
        @Schema(description = "PENDING / ACCEPTED / REJECTED") String status,
        @Schema(description = "用户主张的可接受答案") String proposedAnswer,
        @Schema(description = "用户申诉理由") String reason,
        @Schema(description = "AI 初裁结论") String decisionVerdict,
        @Schema(description = "裁决理由") String decisionReason,
        @Schema(description = "裁决者 LLM/ADMIN") String decidedBy,
        @Schema(description = "裁决时间") Instant decidedAt,
        @Schema(description = "初裁模型快照") String modelSnapshot,
        @Schema(description = "初裁 prompt 版本 id") Long promptVersionId,
        @Schema(description = "申诉时间") Instant createdAt,
        @Schema(description = "题目中文原句") String sentenceZh,
        @Schema(description = "申诉人用户名") String username) {}

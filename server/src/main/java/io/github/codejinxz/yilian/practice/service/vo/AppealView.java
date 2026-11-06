package io.github.codejinxz.yilian.practice.service.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 申诉/不认同提交回执（API §6.4/§6.5）：定位信息、状态与已生成的「AI 意见」。
 *
 * <p>025 起申诉提交同步触发 LLM 初裁：成功时回执带 verdict/decisionReason/decidedBy/decidedAt， 用户在批注行即可看到 AI
 * 意见与「等待人工终审」的状态；初裁失败/限流/未配置模型时这四个字段为 null（不阻塞提交，status 仍 PENDING）。DISPUTE 回执四字段恒为
 * null（排除语义，无裁决链路）。
 *
 * @param appealId 记录 id
 * @param status 状态：APPEAL 为 PENDING（待裁决），DISPUTE 恒为 ACCEPTED
 * @param kind 类型：APPEAL / DISPUTE
 * @param annotationKey 批注键（slot:N / free:0）
 * @param verdict AI 初裁结论：SUPPORT / OPPOSE / UNCERTAIN；初裁失败或未生成为 null
 * @param decisionReason 可展示的裁决理由：初裁成功=AI 理由，终审后=管理员终审理由；未生成为 null
 * @param decidedBy 裁决者：LLM（初裁成功）/ ADMIN（终审）；未生成为 null
 * @param decidedAt 裁决时间；未生成为 null
 */
@Schema(description = "申诉/不认同回执：status 为 PENDING（申诉待裁决）或 ACCEPTED（不认同即时生效）")
public record AppealView(
        @Schema(description = "记录 id") Long appealId,
        @Schema(description = "PENDING / ACCEPTED / REJECTED") String status,
        @Schema(description = "APPEAL / DISPUTE") String kind,
        @Schema(description = "批注键 slot:N / free:0") String annotationKey,
        @Schema(description = "AI 初裁结论 SUPPORT/OPPOSE/UNCERTAIN；未生成为 null") String verdict,
        @Schema(description = "可展示的裁决理由；未生成为 null") String decisionReason,
        @Schema(description = "裁决者 LLM/ADMIN；未生成为 null") String decidedBy,
        @Schema(description = "裁决时间；未生成为 null") Instant decidedAt) {}

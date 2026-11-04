package io.github.codejinxz.yilian.practice.service.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 申诉/不认同提交回执（API §6.4/§6.5）：只回定位信息与状态，不回批改内容。
 *
 * @param appealId 记录 id
 * @param status 状态：APPEAL 为 PENDING（待裁决），DISPUTE 恒为 ACCEPTED
 * @param kind 类型：APPEAL / DISPUTE
 * @param annotationKey 批注键（slot:N / free:0）
 */
@Schema(description = "申诉/不认同回执：status 为 PENDING（申诉待裁决）或 ACCEPTED（不认同即时生效）")
public record AppealView(
        @Schema(description = "记录 id") Long appealId,
        @Schema(description = "PENDING / ACCEPTED") String status,
        @Schema(description = "APPEAL / DISPUTE") String kind,
        @Schema(description = "批注键 slot:N / free:0") String annotationKey) {}

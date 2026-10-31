package io.github.codejinxz.yilian.admin.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 管理端单题状态流转请求体（API §8.8）：停用/恢复/回收，恢复即清软删标记。
 *
 * @param status 目标状态 DRAFT/PUBLISHED/DISABLED/RECYCLED/ARCHIVED
 * @param reason 审计原因，必填 1–255 字符
 */
public record AdminSentenceStatusRequest(
        @NotBlank(message = "status 必填") String status,
        @NotBlank(message = "请填写原因") @Size(max = 255, message = "原因最多 255 字符") String reason) {}

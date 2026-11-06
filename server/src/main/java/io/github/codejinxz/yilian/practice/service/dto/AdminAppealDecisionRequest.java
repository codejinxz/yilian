package io.github.codejinxz.yilian.practice.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 管理端申诉终审请求（API §8.16）。
 *
 * @param status 终审结论：ACCEPTED（通过，进判分 overlay）/ REJECTED（驳回）
 * @param reason 终审理由，必填（裁决可解释、可审计）
 */
public record AdminAppealDecisionRequest(
        @NotBlank(message = "status 不能为空")
                @Pattern(regexp = "ACCEPTED|REJECTED", message = "status 只能为 ACCEPTED 或 REJECTED")
                String status,
        @NotBlank(message = "终审理由必填") @Size(max = 500, message = "reason 超出长度限制") String reason) {}

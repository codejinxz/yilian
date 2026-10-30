package io.github.codejinxz.yilian.practice.service.dto;

import jakarta.validation.constraints.Size;

/**
 * 不认同请求（API §6.5）：「不认同」该批注；提交后立即从错题与统计按 overlay 排除。
 *
 * @param reason 理由，可空
 */
public record DisputeRequest(@Size(max = 500, message = "reason 超出长度限制") String reason) {}

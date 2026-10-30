package io.github.codejinxz.yilian.practice.service.dto;

import jakarta.validation.constraints.Size;

/**
 * 复习卡重置请求（API §6.8）：理由可选，仅进结构化日志。
 *
 * @param reason 用户填写的重置理由
 */
public record ReviewResetRequest(@Size(max = 200, message = "reason 超出长度限制") String reason) {}

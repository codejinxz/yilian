package io.github.codejinxz.yilian.practice.service.dto;

import jakarta.validation.constraints.Size;

/**
 * 顺延请求：理由可选，仅进结构化日志（所有批量操作可见、有理由）。
 *
 * @param reason 用户填写的顺延理由
 */
public record PostponeRequest(@Size(max = 200, message = "reason 超出长度限制") String reason) {}

package io.github.codejinxz.yilian.practice.service.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 管理端查看答题原文请求体（API §8.7）。
 *
 * @param reason 查看原因，必填；随审计行落库，供事后追溯「为什么看原文」
 */
@Schema(description = "查看答题原文请求")
public record AdminAttemptRawRequest(@Schema(description = "查看原因，必填") String reason) {}

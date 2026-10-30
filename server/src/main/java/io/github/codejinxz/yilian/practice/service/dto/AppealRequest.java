package io.github.codejinxz.yilian.practice.service.dto;

import jakarta.validation.constraints.Size;

/**
 * 申诉请求（API §6.4）：「我觉得也对」。两项契约均未强制必填，可空；reason 建议填写。
 *
 * @param proposedAnswer 用户认为可接受的答案，可空
 * @param reason 理由，可空
 */
public record AppealRequest(
        @Size(max = 255, message = "proposedAnswer 超出长度限制") String proposedAnswer,
        @Size(max = 500, message = "reason 超出长度限制") String reason) {}

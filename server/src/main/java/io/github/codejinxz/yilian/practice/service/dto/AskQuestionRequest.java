package io.github.codejinxz.yilian.practice.service.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 题目答疑请求体。
 *
 * <p>长度上限 500 字符：答疑是围绕当前句的一问一答，不是自由对话；同时它是提示词注入面的 第一道闸（配合服务端定界符，见 TutorServiceImpl）。
 *
 * @param question 用户的问题
 */
@Schema(description = "题目答疑请求")
public record AskQuestionRequest(
        @Schema(
                        description = "针对当前句的问题，1-500 字符",
                        example = "这里为什么用 attach importance to？",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                @NotBlank(message = "问题不能为空")
                @Size(max = 500, message = "问题最长 500 字符")
                String question) {}

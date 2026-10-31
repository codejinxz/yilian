package io.github.codejinxz.yilian.admin.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 管理端批量移动题目请求体（API §8.8）：一次操作写一条审计。
 *
 * @param ids 题目 id 列表，1–500 条
 * @param bankId 目标题库 id，数字字符串
 * @param reason 审计原因，必填 1–255 字符
 */
public record AdminSentenceBulkMoveRequest(
        @NotNull(message = "请先选择题目") @Size(min = 1, max = 500, message = "批量操作一次最多 500 条")
                List<String> ids,
        @NotBlank(message = "bankId 必填") String bankId,
        @NotBlank(message = "批量操作必须填写原因") @Size(max = 255, message = "原因最多 255 字符")
                String reason) {}

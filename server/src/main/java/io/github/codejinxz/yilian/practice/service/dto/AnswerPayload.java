package io.github.codejinxz.yilian.practice.service.dto;

import jakarta.validation.constraints.NotNull;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 答案载荷（DATA-MODEL §6.4）：填空带 slots、整句带 text，二选一。
 *
 * @param schemaVersion 载荷结构版本，当前必须为 1
 * @param mode 载荷自述模式，仅冗余校验用，可空
 * @param slots 逐槽答案；CLOZE 模式使用
 * @param text 整句译文；FREE 模式使用
 */
public record AnswerPayload(
        @NotNull(message = "answer.schemaVersion 不能为空") Integer schemaVersion,
        String mode,
        @Size(max = 32, message = "slots 数量超出限制") List<Slot> slots,
        @Size(max = 2000, message = "text 超出长度限制") String text) {

    /** 单槽答案；value 允许空串（未填写也要判分，批注「未填写」）。 */
    public record Slot(
            @NotNull(message = "slot.chunkIndex 不能为空") Integer chunkIndex,
            @Size(max = 500, message = "答案超出长度限制") String value) {}
}

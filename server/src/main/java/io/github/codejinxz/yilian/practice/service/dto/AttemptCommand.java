package io.github.codejinxz.yilian.practice.service.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 提交作答命令（API §6.2 请求体）；attemptId 是客户端生成的幂等键。
 *
 * @param attemptId UUID，幂等键；同 id 同载荷重放、异载荷 40903
 * @param sentenceId 题目 ULID
 * @param sentenceVersionId 提交时客户端持有的题目版本 id（字符串化的 BIGINT），必须与当前版本一致
 * @param mode 练习模式 CLOZE/FREE
 * @param kind 提交类型 FIRST/RETRY/REVEAL
 * @param retryOfId 重打来源 attempt，仅 kind=RETRY 必填
 * @param answer 答案载荷，见 DATA-MODEL §6.4
 * @param hintsUsed 提示次数 0–3，缺省 0
 * @param durationMs 作答耗时 0–86400000ms，可空
 * @param clientCreatedAt 客户端时间，仅离线排序用，可空
 */
public record AttemptCommand(
        @NotBlank(message = "attemptId 不能为空")
                @Pattern(
                        regexp =
                                "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
                        message = "attemptId 必须是 UUID")
                String attemptId,
        @NotBlank(message = "sentenceId 不能为空") @Size(max = 26) String sentenceId,
        @NotBlank(message = "sentenceVersionId 不能为空") String sentenceVersionId,
        @NotNull(message = "mode 不能为空") Mode mode,
        @NotNull(message = "kind 不能为空") Kind kind,
        String retryOfId,
        @NotNull(message = "answer 不能为空") @Valid AnswerPayload answer,
        @Min(value = 0, message = "hintsUsed 不能为负") @Max(value = 3, message = "hintsUsed 最多 3")
                Integer hintsUsed,
        @Min(value = 0, message = "durationMs 不能为负")
                @Max(value = 86_400_000, message = "durationMs 超出范围")
                Integer durationMs,
        Instant clientCreatedAt) {

    /** 练习模式。 */
    public enum Mode {
        /** 语块填空。 */
        CLOZE,
        /** 整句翻译。 */
        FREE
    }

    /** 提交类型。 */
    public enum Kind {
        /** 首答。 */
        FIRST,
        /** 重打。 */
        RETRY,
        /** 看答案。 */
        REVEAL
    }

    /**
     * 提示次数缺省值归一。
     *
     * @return 0–3
     */
    public int effectiveHintsUsed() {
        return hintsUsed == null ? 0 : hintsUsed;
    }
}

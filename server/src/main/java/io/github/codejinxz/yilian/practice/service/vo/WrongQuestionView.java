package io.github.codejinxz.yilian.practice.service.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * 错题列表项（API §6.6）：LEARNING 卡 + 首判未全对，批注带 disputed 覆盖层。
 *
 * @param sentenceId 题目 ULID
 * @param mode 最近练习模式
 * @param state 三档状态（恒 LEARNING）
 * @param dueAt 到期时间
 * @param lastScore 最近提交的首判分数；REVEAL 为 null（模型复核不改错题口径，复核分不出现在这里）
 * @param lastAttemptId 最近提交 id
 * @param wrongCount 派生未全对次数（首判 score&lt;100 的提交数）
 * @param tags 考点标签
 * @param zh 中文原句（当前版本）
 * @param annotations 未全对批注（includeDisputed=false 时已排除 disputed）
 */
@Schema(description = "错题视图：annotations 为可解释批注，disputed 批注按参数过滤")
public record WrongQuestionView(
        @Schema(description = "题目 id") String sentenceId,
        @Schema(description = "模式") String mode,
        @Schema(description = "三档状态") String state,
        @Schema(description = "到期时间") Instant dueAt,
        @Schema(description = "首判分数，REVEAL 为 null") Integer lastScore,
        @Schema(description = "最近提交 id") String lastAttemptId,
        @Schema(description = "未全对次数") long wrongCount,
        @Schema(description = "考点标签") List<String> tags,
        @Schema(description = "中文原句") String zh,
        @Schema(description = "未全对批注") List<WrongAnnotationView> annotations) {

    /**
     * 错题批注投影。
     *
     * @param key 批注键
     * @param tag 考点标签
     * @param message 批注文案
     * @param disputed 是否已被用户 DISPUTE 排除
     */
    @Schema(description = "错题批注")
    public record WrongAnnotationView(
            @Schema(description = "批注键") String key,
            @Schema(description = "考点标签") String tag,
            @Schema(description = "文案") String message,
            @Schema(description = "是否已排除") boolean disputed) {}
}

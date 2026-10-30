package io.github.codejinxz.yilian.practice.service.vo;

import io.github.codejinxz.yilian.practice.grading.GradingResult;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 提交/详情响应（API §6.2 / §6.3 同构）。
 *
 * @param attemptId 幂等键
 * @param sentenceId 题目 ULID
 * @param sentenceVersionId 版本行 id，字符串化
 * @param mode 模式 CLOZE/FREE
 * @param kind 提交类型 FIRST/RETRY/REVEAL
 * @param score 得分；REVEAL 为 null
 * @param maxScore 满分，恒 100
 * @param result 判分结果
 * @param grading 判分元数据（最新 sequence）
 * @param review 复习卡投影；未触发排程（RETRY、新题全对、已有 FIRST 后的 REVEAL）时为 null
 * @param reference 参考译文与解析
 * @param nextAction 客户端下一步动作提示
 * @param replayed 是否幂等重放
 */
@Schema(description = "作答提交响应：批改、排程与参考答案一并返回")
public record AttemptView(
        @Schema(description = "答题 id") String attemptId,
        @Schema(description = "题目 id") String sentenceId,
        @Schema(description = "版本行 id") String sentenceVersionId,
        @Schema(description = "模式") String mode,
        @Schema(description = "提交类型") String kind,
        @Schema(description = "得分，REVEAL 为 null") Integer score,
        @Schema(description = "满分") int maxScore,
        @Schema(description = "判分结果") GradingResult result,
        @Schema(description = "判分元数据") GradingView grading,
        @Schema(description = "复习卡，未排程时为 null") ReviewView review,
        @Schema(description = "参考译文") ReferenceView reference,
        @Schema(description = "下一步动作") NextActionView nextAction,
        @Schema(description = "是否幂等重放") boolean replayed) {

    /**
     * 判分元数据。
     *
     * @param id 判分行 id
     * @param sequence 判分序号
     * @param grader 判分器，LOCAL
     * @param normalizerVersion 归一化版本
     * @param rubricVersion 评分口径版本
     * @param createdAt 判分时间
     */
    @Schema(description = "判分元数据")
    public record GradingView(
            @Schema(description = "判分行 id") Long id,
            @Schema(description = "判分序号") int sequence,
            @Schema(description = "判分器") String grader,
            @Schema(description = "归一化版本") String normalizerVersion,
            @Schema(description = "评分口径版本") String rubricVersion,
            @Schema(description = "判分时间") Instant createdAt) {}

    /**
     * 复习卡投影（reviews 表）。
     *
     * @param id 卡 id
     * @param state 三档 UI 状态
     * @param fsrsState FSRS 四态
     * @param dueAt 到期时间
     * @param stability 稳定度
     * @param difficulty 难度
     * @param reps 复习次数
     * @param lapses 遗忘次数
     * @param scheduledDays 排程天数
     * @param rating 本轮四档评分
     */
    @Schema(description = "复习卡投影")
    public record ReviewView(
            @Schema(description = "卡 id") Long id,
            @Schema(description = "三档状态") String state,
            @Schema(description = "FSRS 状态") String fsrsState,
            @Schema(description = "到期时间") Instant dueAt,
            @Schema(description = "稳定度") double stability,
            @Schema(description = "难度") double difficulty,
            @Schema(description = "复习次数") int reps,
            @Schema(description = "遗忘次数") int lapses,
            @Schema(description = "排程天数") int scheduledDays,
            @Schema(description = "本轮评分 1–4") Integer rating) {}

    /**
     * 参考译文与解析。
     *
     * @param ref 参考译文
     * @param note 解析
     */
    @Schema(description = "参考译文与解析")
    public record ReferenceView(
            @Schema(description = "参考译文") String ref, @Schema(description = "解析") String note) {}

    /**
     * 下一步动作提示。
     *
     * @param retryRequired 是否需要重打（score&lt;100 且 kind=FIRST）
     * @param canReveal 是否允许「看答案，记为学习中」
     */
    @Schema(description = "下一步动作提示")
    public record NextActionView(
            @Schema(description = "是否需要重打") boolean retryRequired,
            @Schema(description = "是否可以看答案") boolean canReveal) {}
}

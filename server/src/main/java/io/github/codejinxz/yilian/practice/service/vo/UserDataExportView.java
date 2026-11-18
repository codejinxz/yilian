package io.github.codejinxz.yilian.practice.service.vo;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * 服务端账号导出的练习域切片：本人 attempts、全部判分行、申诉/不认同与复习卡的只读投影。
 *
 * <p>字段名与 DATA-MODEL §5.8–5.11 的列一一对应（camelCase 投影）；answer 以解析后的 JSON 嵌入 （落库即校验过，异常时降级为原文
 * TextNode，保证导出永不因脏数据中断）。不含他人数据， 不含密码哈希/TOTP 材料等凭据投影（那些在 users 表，由 auth 侧组装账户块）。
 *
 * @param attempts 本人答题提交（created_at 升序）
 * @param attemptGradings 本人提交关联的全部判分行（含复核追加的高序号行）
 * @param appeals 本人申诉/不认同记录（created_at 升序）
 * @param reviewCards 本人复习卡（一句一卡投影，FSRS 当前态）
 */
public record UserDataExportView(
        List<AttemptLine> attempts,
        List<GradingLine> attemptGradings,
        List<AppealLine> appeals,
        List<ReviewCardLine> reviewCards) {

    /**
     * 答题提交行。
     *
     * @param id 提交 UUID（幂等键）
     * @param userId 用户 id
     * @param deviceId 设备 id，可空
     * @param sentenceId 题目 ULID
     * @param sentenceVersionId 题目版本行 id
     * @param questionVersion 题面版本
     * @param mode 模式（CLOZE/FREE）
     * @param kind 提交类型（FIRST/RETRY/REVEAL）
     * @param retryOfId 重打来源提交，仅 RETRY 非空
     * @param answer 答案载荷（解析后的 JSON）
     * @param answerHash 载荷 SHA-256（幂等重放判定依据）
     * @param hintsUsed 提示次数
     * @param durationMs 耗时毫秒，可空
     * @param clientCreatedAt 客户端时间，可空
     * @param createdAt 服务端落库时间
     */
    public record AttemptLine(
            String id,
            Long userId,
            String deviceId,
            String sentenceId,
            Long sentenceVersionId,
            int questionVersion,
            String mode,
            String kind,
            String retryOfId,
            JsonNode answer,
            String answerHash,
            int hintsUsed,
            Integer durationMs,
            Instant clientCreatedAt,
            Instant createdAt) {}

    /**
     * 判分记录行（append-only，一次提交可有多行）。
     *
     * @param id 判分行 id
     * @param attemptId 提交 id
     * @param sequence 判分序号（1=首判）
     * @param score 分数，REVEAL 为 null
     * @param maxScore 满分
     * @param result 判分结果 JSON
     * @param grader 判分器（LOCAL/MODEL）
     * @param normalizerVersion 归一化版本
     * @param rubricVersion 评分标准版本
     * @param modelSnapshot 模型快照，可空
     * @param latencyMs 模型耗时毫秒，可空
     * @param promptVersionId prompt 注册表版本 id，可空
     * @param createdAt 判分时间
     */
    public record GradingLine(
            Long id,
            String attemptId,
            int sequence,
            Integer score,
            int maxScore,
            String result,
            String grader,
            String normalizerVersion,
            String rubricVersion,
            String modelSnapshot,
            Integer latencyMs,
            Long promptVersionId,
            Instant createdAt) {}

    /**
     * 申诉/不认同行。
     *
     * @param id 记录 id
     * @param attemptId 提交 id
     * @param annotationKey 批注 key
     * @param sentenceId 题目 ULID
     * @param chunkIndex 语块下标
     * @param userId 用户 id
     * @param kind 类型（APPEAL/DISPUTE）
     * @param status 状态（PENDING/ACCEPTED/REJECTED）
     * @param proposedAnswer 主张答案，可空
     * @param reason 申诉理由，可空
     * @param decision 裁决原文 JSON，可空
     * @param decisionReason 可展示裁决理由，可空
     * @param decidedBy 裁决者（LLM/ADMIN），可空
     * @param decidedAt 裁决时间，可空
     * @param modelSnapshot 模型快照，可空
     * @param promptVersionId prompt 版本 id，可空
     * @param createdAt 提交时间
     */
    public record AppealLine(
            Long id,
            String attemptId,
            String annotationKey,
            String sentenceId,
            int chunkIndex,
            Long userId,
            String kind,
            String status,
            String proposedAnswer,
            String reason,
            String decision,
            String decisionReason,
            String decidedBy,
            Instant decidedAt,
            String modelSnapshot,
            Long promptVersionId,
            Instant createdAt) {}

    /**
     * 复习卡行（FSRS 当前投影，一句一卡）。
     *
     * @param id 卡 id
     * @param userId 用户 id
     * @param sentenceId 题目 ULID
     * @param mode 模式（CLOZE/FREE）
     * @param state 学习态（NEW/LEARNING/MASTERED）
     * @param fsrsState FSRS 态（NEW/LEARNING/REVIEW/RELEARNING）
     * @param dueAt 下次到期时间
     * @param stability FSRS 稳定性
     * @param difficulty FSRS 难度
     * @param reps 复习次数
     * @param lapses 遗忘次数
     * @param elapsedDays 距上次复习天数
     * @param scheduledDays 排程间隔天数
     * @param lastRating 最近评分，未复习为 null
     * @param lastReviewAt 最近复习时间，未复习为 null
     * @param lastAttemptId 最近关联提交 id，可空
     * @param createdAt 建卡时间
     */
    public record ReviewCardLine(
            Long id,
            Long userId,
            String sentenceId,
            String mode,
            String state,
            String fsrsState,
            Instant dueAt,
            double stability,
            double difficulty,
            int reps,
            int lapses,
            int elapsedDays,
            int scheduledDays,
            Integer lastRating,
            Instant lastReviewAt,
            String lastAttemptId,
            Instant createdAt) {}
}

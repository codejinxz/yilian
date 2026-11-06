package io.github.codejinxz.yilian.practice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 申诉/不认同记录（appeals 表）；同一 (attempt, annotation_key, kind) 唯一。
 *
 * <p>DISPUTE 立即生效（status=ACCEPTED），错题与统计按 overlay 排除；APPEAL 留 PENDING 待裁决。 APPEAL 的裁决分两级：LLM 初裁只落
 * decision/decision_reason（+快照与 prompt 版本）等「AI 意见」列， status 不动；人工终审才落
 * status（ACCEPTED/REJECTED）与终审理由。 裁决列的 UPDATE 只经 Dao 的定向 UPDATE 语句（只碰裁决列，不触碰判分/提交数据）。
 */
@Entity
@Table(name = "appeals")
public class Appeal {

    /** 记录类型。 */
    public enum AppealKind {
        /** 申诉（待裁决）。 */
        APPEAL,
        /** 不认同（立即排除）。 */
        DISPUTE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "attempt_id", nullable = false, length = 36)
    private String attemptId;

    @Column(name = "annotation_key", nullable = false, length = 64)
    private String annotationKey;

    @Column(name = "sentence_id", nullable = false, length = 26)
    private String sentenceId;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AppealKind kind;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "proposed_answer", length = 255)
    private String proposedAnswer;

    /** 理由；契约（API §6.4/§6.5）未强制必填，库里可空，故不标 nullable=false。 */
    @Column(length = 500)
    private String reason;

    /** LLM 初裁原文 JSON（{"verdict":...,"reason":...} 或占位 {"verdict":"UNAVAILABLE"}）；人工终审不改写本列。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "decision")
    private String decision;

    /** 可展示的裁决理由：初裁=AI 理由/占位文案；终审=管理员填写的终审理由（覆盖语义，读端按 decidedBy 区分来源）。 */
    @Column(name = "decision_reason", length = 500)
    private String decisionReason;

    /** 裁决者：LLM（初裁成功）/ ADMIN（人工终审）；初裁失败与占位路径为 NULL（无 AI 意见）。 */
    @Column(name = "decided_by", length = 16)
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    /** 产生初裁的模型快照（providerId:model）；初裁成功才落值。 */
    @Column(name = "model_snapshot", length = 128)
    private String modelSnapshot;

    /** 产生初裁的 prompt 注册表版本 id（prompt_versions.id）；初裁成功才落值。 */
    @Column(name = "prompt_version_id")
    private Long promptVersionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Appeal() {
        // 供 JPA 反射实例化
    }

    /**
     * 创建申诉/不认同记录。
     *
     * @param attemptId 提交 id
     * @param annotationKey 批注 key（slot:N / free:0）
     * @param sentenceId 题目 ULID
     * @param chunkIndex 批注语块下标
     * @param userId 用户 id
     * @param kind 类型
     * @param proposedAnswer 建议答案，可空
     * @param reason 理由
     * @param now 服务端时钟
     * @return 未持久化的记录
     */
    public static Appeal create(
            String attemptId,
            String annotationKey,
            String sentenceId,
            int chunkIndex,
            Long userId,
            AppealKind kind,
            String proposedAnswer,
            String reason,
            Instant now) {
        Appeal appeal = new Appeal();
        appeal.attemptId = attemptId;
        appeal.annotationKey = annotationKey;
        appeal.sentenceId = sentenceId;
        appeal.chunkIndex = chunkIndex;
        appeal.userId = userId;
        appeal.kind = kind;
        appeal.status = kind == AppealKind.DISPUTE ? "ACCEPTED" : "PENDING";
        appeal.proposedAnswer = proposedAnswer;
        appeal.reason = reason;
        appeal.createdAt = now;
        return appeal;
    }

    /**
     * 主键。
     *
     * @return 自增 id
     */
    public Long getId() {
        return id;
    }

    /**
     * 提交 id。
     *
     * @return UUID
     */
    public String getAttemptId() {
        return attemptId;
    }

    /**
     * 批注 key。
     *
     * @return slot:N / free:0
     */
    public String getAnnotationKey() {
        return annotationKey;
    }

    /**
     * 题目 ULID。
     *
     * @return 题目 id
     */
    public String getSentenceId() {
        return sentenceId;
    }

    /**
     * 语块下标。
     *
     * @return 下标
     */
    public int getChunkIndex() {
        return chunkIndex;
    }

    /**
     * 类型。
     *
     * @return APPEAL / DISPUTE
     */
    public AppealKind getKind() {
        return kind;
    }

    /**
     * 申诉人 id。
     *
     * @return 用户 id
     */
    public Long getUserId() {
        return userId;
    }

    /**
     * 状态。
     *
     * @return PENDING / ACCEPTED / REJECTED
     */
    public String getStatus() {
        return status;
    }

    /**
     * 用户主张的可接受答案。
     *
     * @return 建议答案；DISPUTE 与未提供时为 null
     */
    public String getProposedAnswer() {
        return proposedAnswer;
    }

    /**
     * 申诉理由。
     *
     * @return 理由；未提供时为 null
     */
    public String getReason() {
        return reason;
    }

    /**
     * LLM 初裁原文 JSON。
     *
     * @return {"verdict":...} 或占位 {"verdict":"UNAVAILABLE"}；未初裁为 null
     */
    public String getDecision() {
        return decision;
    }

    /**
     * 可展示的裁决理由。
     *
     * @return 初裁理由或终审理由；未裁决为 null
     */
    public String getDecisionReason() {
        return decisionReason;
    }

    /**
     * 裁决者。
     *
     * @return LLM / ADMIN；初裁失败与占位为 null
     */
    public String getDecidedBy() {
        return decidedBy;
    }

    /**
     * 裁决时间。
     *
     * @return UTC；未裁决为 null
     */
    public Instant getDecidedAt() {
        return decidedAt;
    }

    /**
     * 产生初裁的模型快照。
     *
     * @return providerId:model；初裁成功才落值
     */
    public String getModelSnapshot() {
        return modelSnapshot;
    }

    /**
     * 产生初裁的 prompt 注册表版本 id。
     *
     * @return prompt_versions.id；初裁成功才落值
     */
    public Long getPromptVersionId() {
        return promptVersionId;
    }
}

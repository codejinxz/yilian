package io.github.codejinxz.yilian.practice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 申诉/不认同记录（appeals 表）；同一 (attempt, annotation_key, kind) 唯一。
 *
 * <p>DISPUTE 立即生效（status=ACCEPTED），错题与统计按 overlay 排除；APPEAL 留 PENDING 待 M3 裁决。
 */
@Entity
@Table(name = "appeals")
public class Appeal {

    /** 记录类型。 */
    public enum AppealKind {
        /** 申诉（M3 裁决）。 */
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
}

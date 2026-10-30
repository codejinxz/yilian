package io.github.codejinxz.yilian.practice.entity;

import io.github.codejinxz.yilian.common.util.StringUtils;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;

/**
 * 答题提交（append-only，attempts 表）；attemptId 即幂等键。
 *
 * <p>触发器 trg_attempts_no_update/no_delete 保证只增不改；answer_hash 用于幂等 重放判定（同载荷重放、异载荷 40903）。
 */
@Entity
@Table(name = "attempts")
public class Attempt {

    /** 答题模式。 */
    public enum AttemptMode {
        /** 语块填空。 */
        CLOZE,
        /** 整句翻译。 */
        FREE
    }

    /** 提交类型。 */
    public enum AttemptKind {
        /** 首答。 */
        FIRST,
        /** 重打。 */
        RETRY,
        /** 看答案。 */
        REVEAL
    }

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "device_id", length = 64)
    private String deviceId;

    @Column(name = "sentence_id", nullable = false, length = 26)
    private String sentenceId;

    @Column(name = "sentence_version_id", nullable = false)
    private Long sentenceVersionId;

    @Column(name = "question_version", nullable = false)
    private int questionVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AttemptMode mode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AttemptKind kind;

    @Column(name = "retry_of_id", length = 36)
    private String retryOfId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String answer;

    @Column(name = "answer_hash", nullable = false, length = 64)
    private String answerHash;

    @Column(name = "hints_used", nullable = false)
    private short hintsUsed;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "client_created_at")
    private Instant clientCreatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Attempt() {
        // 供 JPA 反射实例化
    }

    private Attempt(
            String id,
            Long userId,
            String deviceId,
            String sentenceId,
            Long sentenceVersionId,
            int questionVersion,
            AttemptMode mode,
            AttemptKind kind,
            String retryOfId,
            String answer,
            String answerHash,
            short hintsUsed,
            Integer durationMs,
            Instant clientCreatedAt,
            Instant now) {
        this.id = id;
        this.userId = userId;
        this.deviceId = deviceId;
        this.sentenceId = sentenceId;
        this.sentenceVersionId = sentenceVersionId;
        this.questionVersion = questionVersion;
        this.mode = mode;
        this.kind = kind;
        this.retryOfId = retryOfId;
        this.answer = answer;
        this.answerHash = answerHash;
        this.hintsUsed = hintsUsed;
        this.durationMs = durationMs;
        this.clientCreatedAt = clientCreatedAt;
        this.createdAt = now;
    }

    /**
     * 创建一次提交记录。
     *
     * @param id UUID 幂等键
     * @param userId 用户 id
     * @param deviceId 设备 id，可空
     * @param sentenceId 题目 ULID
     * @param sentenceVersionId 版本行 id
     * @param questionVersion 题面版本
     * @param mode 模式
     * @param kind 提交类型
     * @param retryOfId 重打来源 attempt，仅 RETRY 时非空
     * @param answer 答案 JSON 文本
     * @param answerHash 载荷哈希
     * @param hintsUsed 提示次数 0–3
     * @param durationMs 耗时毫秒，可空
     * @param clientCreatedAt 客户端时间，可空
     * @param now 服务端时钟
     * @return 未持久化的提交
     */
    public static Attempt create(
            String id,
            Long userId,
            String deviceId,
            String sentenceId,
            Long sentenceVersionId,
            int questionVersion,
            AttemptMode mode,
            AttemptKind kind,
            String retryOfId,
            String answer,
            String answerHash,
            short hintsUsed,
            Integer durationMs,
            Instant clientCreatedAt,
            Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(sentenceId, "sentenceId");
        Objects.requireNonNull(answer, "answer");
        Objects.requireNonNull(answerHash, "answerHash");
        Objects.requireNonNull(now, "now");
        if (StringUtils.isBlank(answerHash)) {
            throw new IllegalArgumentException("answerHash 不能为空");
        }
        if (kind == AttemptKind.RETRY && StringUtils.isBlank(retryOfId)) {
            throw new IllegalArgumentException("RETRY 必须携带 retryOfId");
        }
        return new Attempt(
                id,
                userId,
                deviceId,
                sentenceId,
                sentenceVersionId,
                questionVersion,
                mode,
                kind,
                retryOfId,
                answer,
                answerHash,
                hintsUsed,
                durationMs,
                clientCreatedAt,
                now);
    }

    /**
     * 主键。
     *
     * @return UUID
     */
    public String getId() {
        return id;
    }

    /**
     * 用户 id。
     *
     * @return 用户 id
     */
    public Long getUserId() {
        return userId;
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
     * 版本行 id。
     *
     * @return 版本 id
     */
    public Long getSentenceVersionId() {
        return sentenceVersionId;
    }

    /**
     * 题面版本。
     *
     * @return 版本号
     */
    public int getQuestionVersion() {
        return questionVersion;
    }

    /**
     * 模式。
     *
     * @return 模式
     */
    public AttemptMode getMode() {
        return mode;
    }

    /**
     * 提交类型。
     *
     * @return 类型
     */
    public AttemptKind getKind() {
        return kind;
    }

    /**
     * 答案 JSON 文本。
     *
     * @return JSON
     */
    public String getAnswer() {
        return answer;
    }

    /**
     * 载荷哈希。
     *
     * @return SHA-256
     */
    public String getAnswerHash() {
        return answerHash;
    }

    /**
     * 提示次数。
     *
     * @return 0–3
     */
    public short getHintsUsed() {
        return hintsUsed;
    }

    /**
     * 耗时毫秒。
     *
     * @return 可空
     */
    public Integer getDurationMs() {
        return durationMs;
    }

    /**
     * 创建时间。
     *
     * @return UTC
     */
    public Instant getCreatedAt() {
        return createdAt;
    }
}

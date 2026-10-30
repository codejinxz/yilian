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
 * FSRS 排程日志（review_logs 表，append-only）；attempt 幂等由 uk_review_log_attempt 保证。
 *
 * <p>reset 不写本表（无评分语义）；weights_version_id 指向 V9 种子的 GLOBAL 原型权重行。
 */
@Entity
@Table(name = "review_logs")
public class ReviewLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "review_id", nullable = false)
    private Long reviewId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "sentence_id", nullable = false, length = 26)
    private String sentenceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Attempt.AttemptMode mode;

    @Column(name = "attempt_id", nullable = false, length = 36)
    private String attemptId;

    @Column(nullable = false)
    private short rating;

    @Enumerated(EnumType.STRING)
    @Column(name = "state_before", nullable = false, length = 16)
    private Review.FsrsState stateBefore;

    @Enumerated(EnumType.STRING)
    @Column(name = "state_after", nullable = false, length = 16)
    private Review.FsrsState stateAfter;

    @Column(name = "due_before")
    private Instant dueBefore;

    @Column(name = "due_after", nullable = false)
    private Instant dueAfter;

    @Column(name = "stability_before")
    private Double stabilityBefore;

    @Column(name = "stability_after", nullable = false)
    private double stabilityAfter;

    @Column(name = "difficulty_before")
    private Double difficultyBefore;

    @Column(name = "difficulty_after", nullable = false)
    private double difficultyAfter;

    @Column(name = "elapsed_days", nullable = false)
    private int elapsedDays;

    @Column(name = "scheduled_days", nullable = false)
    private int scheduledDays;

    @Column(name = "review_at", nullable = false)
    private Instant reviewAt;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "weights_version_id", nullable = false)
    private Long weightsVersionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ReviewLog() {
        // 供 JPA 反射实例化
    }

    private ReviewLog(
            Long reviewId,
            Long userId,
            String sentenceId,
            Attempt.AttemptMode mode,
            String attemptId,
            int rating,
            Review.FsrsState stateBefore,
            Review.FsrsState stateAfter,
            Instant dueBefore,
            Instant dueAfter,
            Double stabilityBefore,
            double stabilityAfter,
            Double difficultyBefore,
            double difficultyAfter,
            int elapsedDays,
            int scheduledDays,
            Instant reviewAt,
            Integer durationMs,
            Long weightsVersionId,
            Instant now) {
        this.reviewId = reviewId;
        this.userId = userId;
        this.sentenceId = sentenceId;
        this.mode = mode;
        this.attemptId = attemptId;
        this.rating = (short) rating;
        this.stateBefore = stateBefore;
        this.stateAfter = stateAfter;
        this.dueBefore = dueBefore;
        this.dueAfter = dueAfter;
        this.stabilityBefore = stabilityBefore;
        this.stabilityAfter = stabilityAfter;
        this.difficultyBefore = difficultyBefore;
        this.difficultyAfter = difficultyAfter;
        this.elapsedDays = elapsedDays;
        this.scheduledDays = scheduledDays;
        this.reviewAt = reviewAt;
        this.durationMs = durationMs;
        this.weightsVersionId = weightsVersionId;
        this.createdAt = now;
    }

    /**
     * 新建卡排程日志（state_before=NEW）。
     *
     * @param review 新建的复习卡
     * @param attemptId 提交 id
     * @param rating 评分
     * @param durationMs 耗时，可空
     * @param weightsVersionId 权重版本行
     * @param now 服务端时钟
     * @return 未持久化日志
     */
    public static ReviewLog forNewCard(
            Review review,
            String attemptId,
            int rating,
            Integer durationMs,
            Long weightsVersionId,
            Instant now) {
        return new ReviewLog(
                review.getId(),
                review.getUserId(),
                review.getSentenceId(),
                review.getMode(),
                attemptId,
                rating,
                Review.FsrsState.NEW,
                review.getFsrsState(),
                null,
                review.getDueAt(),
                null,
                review.getStability(),
                null,
                review.getDifficulty(),
                0,
                review.getScheduledDays(),
                now,
                durationMs,
                weightsVersionId,
                now);
    }

    /**
     * 既有卡排程日志（记录 before/after）。
     *
     * @param review 更新后的复习卡
     * @param stateBefore 变更前 FSRS 态
     * @param dueBefore 变更前到期
     * @param stabilityBefore 变更前稳定度
     * @param difficultyBefore 变更前难度
     * @param elapsedDays 距上次复习天数
     * @param attemptId 提交 id
     * @param rating 评分
     * @param durationMs 耗时，可空
     * @param weightsVersionId 权重版本行
     * @param now 服务端时钟
     * @return 未持久化日志
     */
    public static ReviewLog forExistingCard(
            Review review,
            Review.FsrsState stateBefore,
            Instant dueBefore,
            Double stabilityBefore,
            Double difficultyBefore,
            int elapsedDays,
            String attemptId,
            int rating,
            Integer durationMs,
            Long weightsVersionId,
            Instant now) {
        return new ReviewLog(
                review.getId(),
                review.getUserId(),
                review.getSentenceId(),
                review.getMode(),
                attemptId,
                rating,
                stateBefore,
                review.getFsrsState(),
                dueBefore,
                review.getDueAt(),
                stabilityBefore,
                review.getStability(),
                difficultyBefore,
                review.getDifficulty(),
                elapsedDays,
                review.getScheduledDays(),
                now,
                durationMs,
                weightsVersionId,
                now);
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
     * 四档评分。
     *
     * @return 1–4
     */
    public short getRating() {
        return rating;
    }
}

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
 * 复习卡（reviews 表，可变投影）；Q1 一句一卡：唯一键 (user_id, sentence_id)，
 * mode 为最近练习模式。
 *
 * <p>state 是三档 UI 投影（NEW/LEARNING/MASTERED），fsrsState 是 FSRS 四态；
 * 排程四分支与本地引擎逐值一致（切片设计 §3.6）。
 */
@Entity
@Table(name = "reviews")
public class Review {

    /** UI 三档状态。 */
    public enum ReviewState {
        /** 未入队/已重置。 */
        NEW,
        /** 学习中。 */
        LEARNING,
        /** 已归档。 */
        MASTERED
    }

    /** FSRS 四态。 */
    public enum FsrsState {
        /** 未学习。 */
        NEW,
        /** 学习中。 */
        LEARNING,
        /** 复习（归档）。 */
        REVIEW,
        /** 重学。 */
        RELEARNING
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "sentence_id", nullable = false, length = 26)
    private String sentenceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Attempt.AttemptMode mode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReviewState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "fsrs_state", nullable = false, length = 16)
    private FsrsState fsrsState;

    @Column(name = "due_at", nullable = false)
    private Instant dueAt;

    @Column(nullable = false)
    private double stability;

    @Column(nullable = false)
    private double difficulty;

    @Column(nullable = false)
    private int reps;

    @Column(nullable = false)
    private int lapses;

    @Column(name = "elapsed_days", nullable = false)
    private int elapsedDays;

    @Column(name = "scheduled_days", nullable = false)
    private int scheduledDays;

    @Column(name = "last_rating")
    private Integer lastRating;

    @Column(name = "last_review_at")
    private Instant lastReviewAt;

    @Column(name = "last_attempt_id", length = 36)
    private String lastAttemptId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Review() {
        // 供 JPA 反射实例化
    }

    private Review(Long userId, String sentenceId, Attempt.AttemptMode mode, Instant now) {
        this.userId = userId;
        this.sentenceId = sentenceId;
        this.mode = mode;
        this.state = ReviewState.LEARNING;
        this.fsrsState = FsrsState.LEARNING;
        this.dueAt = now.plusSeconds(86_400);
        this.createdAt = now;
    }

    /**
     * 创建失败入队的复习卡（LEARNING、明天到期、原型初值）。
     *
     * @param userId 用户 id
     * @param sentenceId 题目 ULID
     * @param mode 最近练习模式
     * @param attemptId 触发提交 id
     * @param rating 本轮评分
     * @param now 服务端时钟
     * @return 未持久化的复习卡
     */
    public static Review createFailed(
            Long userId,
            String sentenceId,
            Attempt.AttemptMode mode,
            String attemptId,
            int rating,
            Instant now) {
        Review review = new Review(userId, sentenceId, mode, now);
        review.stability = 2.4;
        review.difficulty = 5.3;
        review.reps = 1;
        review.lapses = 1;
        review.elapsedDays = 0;
        review.scheduledDays = 1;
        review.lastRating = rating;
        review.lastReviewAt = now;
        review.lastAttemptId = attemptId;
        return review;
    }

    /**
     * 是否已存在卡（用于区分新建与更新路径）。
     *
     * @return id 非空返回 true
     */
    public boolean isPersisted() {
        return id != null;
    }

    /**
     * 全对学习分支：interval=min(7, scheduled+2)；≥5 归档。
     *
     * @param attemptId 触发提交 id
     * @param rating 本轮评分
     * @param now 服务端时钟
     */
    public void applyLearningGraduated(String attemptId, int rating, Instant now) {
        int interval = Math.min(7, this.scheduledDays + 2);
        boolean mastered = interval >= 5;
        this.state = mastered ? ReviewState.MASTERED : ReviewState.LEARNING;
        this.fsrsState = mastered ? FsrsState.REVIEW : FsrsState.LEARNING;
        this.dueAt = now.plusSeconds(interval * 86_400L);
        this.stability = mastered ? 40 : 3.2;
        this.difficulty = 5.1;
        this.reps = this.reps + 1;
        this.scheduledDays = interval;
        this.lastRating = rating;
        this.lastReviewAt = now;
        this.lastAttemptId = attemptId;
    }

    /** 失败分支：回炉 LEARNING、明天到期。 */
    public void applyFailed(String attemptId, int rating, Instant now) {
        this.state = ReviewState.LEARNING;
        this.fsrsState = FsrsState.LEARNING;
        this.dueAt = now.plusSeconds(86_400L);
        this.stability = 2.4;
        this.difficulty = 5.3;
        this.reps = this.reps + 1;
        this.lapses = this.lapses + 1;
        this.elapsedDays = 0;
        this.scheduledDays = 1;
        this.lastRating = rating;
        this.lastReviewAt = now;
        this.lastAttemptId = attemptId;
    }

    /** 归档全对：只刷新复习痕迹。 */
    public void applyMasteredReview(String attemptId, int rating, Instant now) {
        this.reps = this.reps + 1;
        this.lastRating = rating;
        this.lastReviewAt = now;
        this.lastAttemptId = attemptId;
    }

    /** 重置为 NEW：清零排程痕迹，保留历史日志（reset 不写 review_logs）。 */
    public void reset(Instant now) {
        this.state = ReviewState.NEW;
        this.fsrsState = FsrsState.NEW;
        this.dueAt = now;
        this.stability = 0;
        this.difficulty = 0;
        this.reps = 0;
        this.lapses = 0;
        this.elapsedDays = 0;
        this.scheduledDays = 0;
        this.lastRating = null;
        this.lastReviewAt = null;
        this.lastAttemptId = null;
    }

    /** 更新最近练习模式。 */
    public void touchMode(Attempt.AttemptMode mode) {
        this.mode = mode;
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
     * 最近练习模式。
     *
     * @return 模式
     */
    public Attempt.AttemptMode getMode() {
        return mode;
    }

    /**
     * 三档状态。
     *
     * @return 状态
     */
    public ReviewState getState() {
        return state;
    }

    /**
     * FSRS 状态。
     *
     * @return 状态
     */
    public FsrsState getFsrsState() {
        return fsrsState;
    }

    /**
     * 到期时间。
     *
     * @return UTC
     */
    public Instant getDueAt() {
        return dueAt;
    }

    /**
     * 稳定度。
     *
     * @return 值
     */
    public double getStability() {
        return stability;
    }

    /**
     * 难度。
     *
     * @return 值
     */
    public double getDifficulty() {
        return difficulty;
    }

    /**
     * 复习次数。
     *
     * @return 次数
     */
    public int getReps() {
        return reps;
    }

    /**
     * 遗忘次数。
     *
     * @return 次数
     */
    public int getLapses() {
        return lapses;
    }

    /**
     * 排程天数。
     *
     * @return 天数
     */
    public int getScheduledDays() {
        return scheduledDays;
    }

    /**
     * 最近评分。
     *
     * @return 1–4，可空
     */
    public Integer getLastRating() {
        return lastRating;
    }

    /**
     * 最近复习时间。
     *
     * @return UTC，可空
     */
    public Instant getLastReviewAt() {
        return lastReviewAt;
    }
}

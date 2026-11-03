package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.entity.Attempt;
import io.github.codejinxz.yilian.practice.entity.Review;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 复习卡 Spring Data 仓库，只允许同包 JpaReviewDao 使用。 */
public interface ReviewJpaRepository extends JpaRepository<Review, Long> {

    /**
     * 用户在某题的卡。
     *
     * @param userId 用户 id
     * @param sentenceId 题目 ULID
     * @return 卡
     */
    Optional<Review> findByUserIdAndSentenceId(Long userId, String sentenceId);

    /**
     * 到期队列。
     *
     * @param userId 用户 id
     * @param state 状态
     * @param now 当前时间
     * @return 到期卡
     */
    List<Review> findByUserIdAndStateAndDueAtLessThanEqualOrderByDueAtAsc(
            Long userId, Review.ReviewState state, Instant now);

    /**
     * 用户全部 LEARNING 卡。
     *
     * @param userId 用户 id
     * @param state 状态
     * @return 学习中卡
     */
    List<Review> findByUserIdAndStateOrderByDueAtAsc(Long userId, Review.ReviewState state);

    /**
     * 到期计数。
     *
     * @param userId 用户 id
     * @param state 状态
     * @param now 当前时间
     * @return 数量
     */
    long countByUserIdAndStateAndDueAtLessThanEqual(
            Long userId, Review.ReviewState state, Instant now);

    /**
     * 用户全部卡。
     *
     * @param userId 用户 id
     * @return 全部卡
     */
    List<Review> findByUserId(Long userId);

    /**
     * 排程列的条件更新（018 AC-4）：乐观锁令牌为 last_review_at 旧值，不新增版本列（V11
     * 保护路径未批）。无版本列方案的选型裁决见实体/服务层注释：排程路径上卡必有非空 last_review_at（建卡与每次排程都写它，NEW 卡在上游被排除），可作天然令牌； OR
     * 分支防御性兼容空值（ JPQL 参数 IS NULL）。
     *
     * @param id 卡 id
     * @param mode 最近练习模式
     * @param state 三档状态
     * @param fsrsState FSRS 四态
     * @param dueAt 到期时间
     * @param stability 稳定度
     * @param difficulty 难度
     * @param reps 复习次数
     * @param lapses 遗忘次数
     * @param elapsedDays 距上次复习天数
     * @param scheduledDays 排程天数
     * @param lastRating 最近评分，可空
     * @param lastReviewAt 本次排程时间（新值）
     * @param lastAttemptId 触发排程的 attempt id
     * @param expectedLastReviewAt 读取时的 last_review_at 旧值（令牌）
     * @return 受影响行数：1 成功，0 令牌过期
     */
    @Modifying
    @Query(
            """
            update Review r
            set r.mode = :mode,
                r.state = :state,
                r.fsrsState = :fsrsState,
                r.dueAt = :dueAt,
                r.stability = :stability,
                r.difficulty = :difficulty,
                r.reps = :reps,
                r.lapses = :lapses,
                r.elapsedDays = :elapsedDays,
                r.scheduledDays = :scheduledDays,
                r.lastRating = :lastRating,
                r.lastReviewAt = :lastReviewAt,
                r.lastAttemptId = :lastAttemptId
            where r.id = :id
              and ((:expectedLastReviewAt is null and r.lastReviewAt is null) or r.lastReviewAt = :expectedLastReviewAt)
            """)
    int updateSchedulingState(
            @Param("id") Long id,
            @Param("mode") Attempt.AttemptMode mode,
            @Param("state") Review.ReviewState state,
            @Param("fsrsState") Review.FsrsState fsrsState,
            @Param("dueAt") Instant dueAt,
            @Param("stability") double stability,
            @Param("difficulty") double difficulty,
            @Param("reps") int reps,
            @Param("lapses") int lapses,
            @Param("elapsedDays") int elapsedDays,
            @Param("scheduledDays") int scheduledDays,
            @Param("lastRating") Short lastRating,
            @Param("lastReviewAt") Instant lastReviewAt,
            @Param("lastAttemptId") String lastAttemptId,
            @Param("expectedLastReviewAt") Instant expectedLastReviewAt);
}

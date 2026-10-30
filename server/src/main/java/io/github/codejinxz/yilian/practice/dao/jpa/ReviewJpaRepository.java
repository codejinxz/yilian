package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.entity.Review;

import org.springframework.data.jpa.repository.JpaRepository;

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
    long countByUserIdAndStateAndDueAtLessThanEqual(Long userId, Review.ReviewState state, Instant now);

    /**
     * 用户全部卡。
     *
     * @param userId 用户 id
     * @return 全部卡
     */
    List<Review> findByUserId(Long userId);
}

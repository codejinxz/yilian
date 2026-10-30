package io.github.codejinxz.yilian.practice.dao;

import io.github.codejinxz.yilian.practice.entity.Review;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 复习卡 Dao：Q1 一句一卡（userId+sentenceId 唯一）。 */
public interface ReviewDao {

    /**
     * 按用户与题目取卡。
     *
     * @param userId 用户 id
     * @param sentenceId 题目 ULID
     * @return 复习卡
     */
    Optional<Review> findByUserIdAndSentenceId(Long userId, String sentenceId);

    /**
     * 按 id 取卡。
     *
     * @param id 卡 id
     * @return 复习卡
     */
    Optional<Review> findById(Long id);

    /**
     * 保存卡。
     *
     * @param review 复习卡
     * @return 持久化后的卡
     */
    Review save(Review review);

    /**
     * 到期队列（LEARNING 且 due_at≤now），due_at 升序。
     *
     * @param userId 用户 id
     * @param now 当前时间
     * @return 到期卡
     */
    List<Review> findDue(Long userId, Instant now);

    /**
     * 用户全部 LEARNING 卡（due_at 升序）。
     *
     * @param userId 用户 id
     * @return 学习中卡
     */
    List<Review> findLearning(Long userId);

    /**
     * 到期卡计数（today.dueCount）。
     *
     * @param userId 用户 id
     * @param now 当前时间
     * @return 数量
     */
    long countDue(Long userId, Instant now);

    /**
     * 用户全部卡（错题列表装配用）。
     *
     * @param userId 用户 id
     * @return 全部卡
     */
    List<Review> findAllByUser(Long userId);
}

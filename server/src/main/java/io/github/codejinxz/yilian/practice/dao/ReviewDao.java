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
     * 保存并立即 flush：并发同句建卡撞 {@code uk_review_card} 的唯一键冲突 必须在调用点被捕获（调用方在 REQUIRES_NEW
     * 内层事务中调用，冲突留在内层干净回滚）。
     *
     * @param review 复习卡
     * @return 持久化后的卡
     */
    Review saveAndFlush(Review review);

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

    /**
     * 排程字段的条件更新（乐观锁）：以传入实体当前值整体覆盖排程列， WHERE 带 {@code last_review_at = expectedLastReviewAt}
     * 令牌，并发更新只允许一个生效。
     *
     * <p>调用方必须先对实体 {@link #detach}，否则持久化上下文在提交时的脏检查会再发一条无条件 UPDATE， 绕过令牌守卫。影响行数为 0
     * 表示令牌过期（其他事务已更新该卡）。
     *
     * @param review 已应用排程变更的卡（数据源为实体当前值）
     * @param expectedLastReviewAt 读取卡时的 last_review_at 旧值（乐观锁令牌）
     * @return 受影响行数：1 成功，0 令牌过期
     */
    int updateSchedulingState(Review review, Instant expectedLastReviewAt);

    /**
     * 把卡从持久化上下文分离：条件更新后的实体不再参与提交期脏检查。
     *
     * @param review 待分离的卡
     */
    void detach(Review review);
}

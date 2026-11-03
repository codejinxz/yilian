package io.github.codejinxz.yilian.practice.dao;

import io.github.codejinxz.yilian.practice.entity.ReviewLog;

import java.util.List;
import java.util.Optional;

/** FSRS 排程日志 Dao（append-only）。 */
public interface ReviewLogDao {

    /**
     * 保存日志。
     *
     * @param log 日志
     * @return 持久化后的日志
     */
    ReviewLog save(ReviewLog log);

    /**
     * 按提交取日志（幂等：uk_review_log_attempt 保证一条）。
     *
     * @param attemptId 提交 id
     * @return 日志
     */
    Optional<ReviewLog> findByAttemptId(String attemptId);

    /**
     * 用户的全部排程日志（FSRS 优化器训练集），按 id 升序（插入序即时间序）。
     *
     * @param userId 用户 id
     * @return 日志列表
     */
    List<ReviewLog> findByUserId(Long userId);

    /**
     * 用户最近的排程日志（FSRS 优化器训练集样本上限），按 id 倒序取前 limit 条，返回时已恢复时间升序。
     *
     * @param userId 用户 id
     * @param limit 最多条数
     * @return 日志列表（时间升序，最多 limit 条）
     */
    List<ReviewLog> findRecentByUserId(Long userId, int limit);
}

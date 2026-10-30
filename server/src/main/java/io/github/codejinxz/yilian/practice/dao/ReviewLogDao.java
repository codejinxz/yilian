package io.github.codejinxz.yilian.practice.dao;

import io.github.codejinxz.yilian.practice.entity.ReviewLog;

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
}

package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.dao.ReviewLogDao;
import io.github.codejinxz.yilian.practice.entity.ReviewLog;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** FSRS 排程日志 Dao 的 JPA 实现。 */
@Repository
public class JpaReviewLogDao implements ReviewLogDao {

    private final ReviewLogJpaRepository logs;

    /**
     * @param logs 排程日志 Spring Data 仓库
     */
    public JpaReviewLogDao(ReviewLogJpaRepository logs) {
        this.logs = logs;
    }

    @Override
    public ReviewLog save(ReviewLog log) {
        return logs.save(log);
    }

    @Override
    public Optional<ReviewLog> findByAttemptId(String attemptId) {
        return logs.findByAttemptId(attemptId);
    }

    @Override
    public List<ReviewLog> findByUserId(Long userId) {
        return logs.findByUserIdOrderByIdAsc(userId);
    }
}

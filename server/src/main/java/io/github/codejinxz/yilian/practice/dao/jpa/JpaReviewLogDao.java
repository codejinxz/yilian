package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.dao.ReviewLogDao;
import io.github.codejinxz.yilian.practice.entity.ReviewLog;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
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

    @Override
    public List<ReviewLog> findRecentByUserId(Long userId, int limit) {
        // 倒序取最近 N 条后翻回时间升序：优化器按卡分组重放记忆状态，输入保持时间序最稳。
        List<ReviewLog> recent =
                new ArrayList<>(
                        logs.findByUserIdOrderByIdDesc(
                                userId, PageRequest.of(0, Math.max(limit, 0))));
        java.util.Collections.reverse(recent);
        return List.copyOf(recent);
    }
}

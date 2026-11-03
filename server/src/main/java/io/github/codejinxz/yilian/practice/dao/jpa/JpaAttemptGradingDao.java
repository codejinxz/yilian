package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.dao.AttemptGradingDao;
import io.github.codejinxz.yilian.practice.entity.AttemptGrading;

import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 判分记录 Dao 的 JPA 实现。 */
@Repository
public class JpaAttemptGradingDao implements AttemptGradingDao {

    private final AttemptGradingJpaRepository gradings;

    /**
     * @param gradings 判分 Spring Data 仓库
     */
    public JpaAttemptGradingDao(AttemptGradingJpaRepository gradings) {
        this.gradings = gradings;
    }

    @Override
    public AttemptGrading save(AttemptGrading grading) {
        return gradings.save(grading);
    }

    @Override
    public List<AttemptGrading> findByAttemptId(String attemptId) {
        return gradings.findByAttemptIdOrderBySequenceDesc(attemptId);
    }

    @Override
    public Optional<AttemptGrading> findLatestByAttemptId(String attemptId) {
        return gradings.findFirstByAttemptIdOrderBySequenceDesc(attemptId);
    }

    @Override
    public List<AttemptGrading> findLatestByAttemptIds(Collection<String> attemptIds) {
        if (attemptIds.isEmpty()) {
            return List.of();
        }
        // 一条 IN 查询取回全部判分，Java 侧按 (attemptId, 最大 sequence) 归并出最新行：
        // 单人区间内判分行数有限，不必为一次装配引入窗口函数或子查询。
        Map<String, AttemptGrading> latest = new LinkedHashMap<>();
        for (AttemptGrading grading : gradings.findByAttemptIdIn(attemptIds)) {
            latest.merge(
                    grading.getAttemptId(),
                    grading,
                    (current, candidate) ->
                            candidate.getSequence() > current.getSequence() ? candidate : current);
        }
        return List.copyOf(latest.values());
    }

    @Override
    public long countWrongByUserAndSentence(Long userId, String sentenceId) {
        return gradings.countWrong(userId, sentenceId);
    }

    @Override
    public boolean existsByAttemptId(String attemptId) {
        return gradings.existsByAttemptId(attemptId);
    }
}

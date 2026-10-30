package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.dao.AttemptGradingDao;

import io.github.codejinxz.yilian.practice.entity.AttemptGrading;

import org.springframework.stereotype.Repository;

import java.util.List;

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
    public long countWrongByUserAndSentence(Long userId, String sentenceId) {
        return gradings.countWrong(userId, sentenceId);
    }

    @Override
    public boolean existsByAttemptId(String attemptId) {
        return gradings.existsByAttemptId(attemptId);
    }
}

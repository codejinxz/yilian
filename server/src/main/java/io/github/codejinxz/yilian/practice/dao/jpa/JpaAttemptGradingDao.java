package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.dao.AttemptGradingDao;
import io.github.codejinxz.yilian.practice.entity.AttemptGrading;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 判分记录 Dao 的 JPA 实现。 */
@Repository
public class JpaAttemptGradingDao implements AttemptGradingDao {

    /** Spring Data 仓储（包内可见）。 */
    interface AttemptGradingJpaRepository extends JpaRepository<AttemptGrading, Long> {

        /**
         * 某提交的判分记录（序号倒序）。
         *
         * @param attemptId 提交 id
         * @return 判分记录
         */
        List<AttemptGrading> findByAttemptIdOrderBySequenceDesc(String attemptId);

        /**
         * 最新判分。
         *
         * @param attemptId 提交 id
         * @return 判分记录
         */
        Optional<AttemptGrading> findFirstByAttemptIdOrderBySequenceDesc(String attemptId);

        /**
         * score&lt;100 的判分数（跨聚合 JPQL 联接）。
         *
         * @param userId 用户 id
         * @param sentenceId 题目 ULID
         * @return 未全对次数
         */
        @Query(
                "select count(g) from AttemptGrading g join Attempt a on a.id = g.attemptId"
                        + " where a.userId = :userId and a.sentenceId = :sentenceId"
                        + " and g.score is not null and g.score < 100")
        long countWrong(
                @Param("userId") Long userId, @Param("sentenceId") String sentenceId);

        /**
         * 是否存在判分。
         *
         * @param attemptId 提交 id
         * @return 存在返回 true
         */
        boolean existsByAttemptId(String attemptId);
    }

    private final AttemptGradingJpaRepository gradings;

    /**
     * @param gradings Spring Data 仓储
     */
    public JpaAttemptGradingDao(AttemptGradingJpaRepository gradings) {
        this.gradings = gradings;
    }

    @Override
    public AttemptGrading save(AttemptGrading grading) {
        return gradings.save(grading);
    }

    @Override
    public List<AttemptGrading> findByAttemptId(Long attemptId) {
        return gradings.findByAttemptIdOrderBySequenceDesc(attemptId.toString());
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

package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.dao.AttemptDao;
import io.github.codejinxz.yilian.practice.entity.Attempt;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** attempt Dao 的 JPA 实现。 */
@Repository
public class JpaAttemptDao implements AttemptDao {

    /** Spring Data 仓储（包内可见）。 */
    interface AttemptJpaRepository extends JpaRepository<Attempt, String> {

        /**
         * 用户在某题的最近提交。
         *
         * @param userId 用户 id
         * @param sentenceId 题目 ULID
         * @return 最近提交
         */
        Optional<Attempt> findFirstByUserIdAndSentenceIdOrderByCreatedAtDesc(
                Long userId, String sentenceId);

        /**
         * 区间内提交数。
         *
         * @param userId 用户 id
         * @param start 起点
         * @param end 终点
         * @return 数量
         */
        long countByUserIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                Long userId, Instant start, Instant end);
    }

    private final AttemptJpaRepository attempts;

    /**
     * @param attempts Spring Data 仓储
     */
    public JpaAttemptDao(AttemptJpaRepository attempts) {
        this.attempts = attempts;
    }

    @Override
    public Optional<Attempt> findById(String id) {
        return attempts.findById(id);
    }

    @Override
    public Attempt save(Attempt attempt) {
        return attempts.save(attempt);
    }

    @Override
    public Optional<Attempt> findLatestByUserAndSentence(Long userId, String sentenceId) {
        return attempts.findFirstByUserIdAndSentenceIdOrderByCreatedAtDesc(userId, sentenceId);
    }

    @Override
    public long countByUserBetween(Long userId, Instant start, Instant end) {
        return attempts.countByUserIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(userId, start, end);
    }
}

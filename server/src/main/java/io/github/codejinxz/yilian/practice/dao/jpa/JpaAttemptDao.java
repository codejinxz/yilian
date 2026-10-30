package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.dao.AttemptDao;
import io.github.codejinxz.yilian.practice.entity.Attempt;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** attempt Dao 的 JPA 实现。 */
@Repository
public class JpaAttemptDao implements AttemptDao {

    private final AttemptJpaRepository attempts;

    /**
     * @param attempts 提交 Spring Data 仓库
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
    public boolean existsByUserAndSentenceAndModeAndKind(
            Long userId, String sentenceId, Attempt.AttemptMode mode, Attempt.AttemptKind kind) {
        return attempts.existsByUserIdAndSentenceIdAndModeAndKind(userId, sentenceId, mode, kind);
    }

    @Override
    public long countByUserBetween(Long userId, Instant start, Instant end) {
        return attempts.countByUserIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                userId, start, end);
    }

    @Override
    public List<Attempt> findByUserBetween(Long userId, Instant start, Instant end) {
        return attempts
                .findByUserIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
                        userId, start, end);
    }

    @Override
    public List<Attempt> findRecentByUser(Long userId, int limit) {
        return attempts.findTop10ByUserIdOrderByCreatedAtDesc(userId).stream()
                .limit(limit)
                .toList();
    }
}

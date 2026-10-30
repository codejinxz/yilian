package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.entity.Attempt;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

import java.util.Optional;

/** 提交 Spring Data 仓库，只允许同包 JpaAttemptDao 使用。 */
public interface AttemptJpaRepository extends JpaRepository<Attempt, String> {

    /**
     * 用户在某题的最近提交。
     *
     * @param userId 用户 id
     * @param sentenceId 题目 ULID
     * @return 最近提交
     */
    Optional<Attempt> findFirstByUserIdAndSentenceIdOrderByCreatedAtDesc(Long userId, String sentenceId);

    /**
     * 用户在某题某模式是否存在指定类型的提交。
     *
     * @param userId 用户 id
     * @param sentenceId 题目 ULID
     * @param mode 模式
     * @param kind 提交类型
     * @return 存在返回 true
     */
    boolean existsByUserIdAndSentenceIdAndModeAndKind(
            Long userId, String sentenceId, Attempt.AttemptMode mode, Attempt.AttemptKind kind);

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

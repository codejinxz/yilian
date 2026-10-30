package io.github.codejinxz.yilian.practice.dao;

import io.github.codejinxz.yilian.practice.entity.Attempt;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 答题提交 Dao：业务层只依赖本接口。
 *
 * <p>attempts append-only（触发器禁 UPDATE/DELETE）；id 幂等键。
 */
public interface AttemptDao {

    /**
     * 按 id 查提交。
     *
     * @param id UUID
     * @return 提交
     */
    Optional<Attempt> findById(String id);

    /**
     * 保存提交（仅首次插入，append-only）。
     *
     * @param attempt 提交
     * @return 持久化后的提交
     */
    Attempt save(Attempt attempt);

    /**
     * 用户在某题的最近一次提交。
     *
     * @param userId 用户 id
     * @param sentenceId 题目 ULID
     * @return 最近提交
     */
    Optional<Attempt> findLatestByUserAndSentence(Long userId, String sentenceId);

    /**
     * 用户在本地日界区间内的提交数（today.doneCount）。
     *
     * @param userId 用户 id
     * @param start 日界起点（UTC）
     * @param end 日界终点（UTC）
     * @return 提交数
     */
    long countByUserBetween(Long userId, Instant start, Instant end);
}

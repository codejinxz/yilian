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
     * 保存提交并立即 flush，让 INSERT 在调用点执行而不是延迟到事务提交。
     *
     * <p>并发同 id 双发时，主键冲突会在调用方的 catch 里被捕获并按幂等语义处理； 若用 {@link #save(Attempt)}，冲突会延迟到 commit 阶段才暴露，绕过
     * catch 直接变成 500。
     *
     * @param attempt 提交
     * @return 持久化后的提交
     */
    Attempt saveAndFlush(Attempt attempt);

    /**
     * 锁定单条提交行（SELECT ... FOR UPDATE），模型复核对同 attempt 的并发复核串行化用（029）。
     *
     * <p>必须在事务内调用：锁随事务提交/回滚释放。行不存在时静默返回（调用方已先做存在性校验）。
     *
     * @param id 提交 UUID
     */
    void lockById(String id);

    /**
     * 用户在某题的最近一次提交。
     *
     * @param userId 用户 id
     * @param sentenceId 题目 ULID
     * @return 最近提交
     */
    Optional<Attempt> findLatestByUserAndSentence(Long userId, String sentenceId);

    /**
     * 用户在某题某模式是否存在指定类型的提交（「没有 FIRST 的 REVEAL」判定用）。
     *
     * @param userId 用户 id
     * @param sentenceId 题目 ULID
     * @param mode 模式
     * @param kind 提交类型
     * @return 存在返回 true
     */
    boolean existsByUserAndSentenceAndModeAndKind(
            Long userId, String sentenceId, Attempt.AttemptMode mode, Attempt.AttemptKind kind);

    /**
     * 用户在本地日界区间内的提交数（today.doneCount）。
     *
     * @param userId 用户 id
     * @param start 日界起点（UTC）
     * @param end 日界终点（UTC）
     * @return 提交数
     */
    long countByUserBetween(Long userId, Instant start, Instant end);

    /**
     * 用户在时间区间内的全部提交（today/analytics 装配，V1 数据量小走内存聚合）。
     *
     * @param userId 用户 id
     * @param start 起点（含，UTC）
     * @param end 终点（不含，UTC）
     * @return 提交列表，created_at 升序
     */
    List<Attempt> findByUserBetween(Long userId, Instant start, Instant end);

    /**
     * 用户最近 N 条提交（analytics.recent）。
     *
     * @param userId 用户 id
     * @param limit 最多条数
     * @return 提交列表，created_at 倒序
     */
    List<Attempt> findRecentByUser(Long userId, int limit);

    /**
     * 用户全部提交（服务端账号导出用，append-only 全量读取）。
     *
     * @param userId 用户 id
     * @return 提交列表，created_at 升序
     */
    List<Attempt> findAllByUser(Long userId);
}

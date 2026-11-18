package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.entity.Attempt;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 提交 Spring Data 仓库，只允许同包 JpaAttemptDao 使用。 */
public interface AttemptJpaRepository extends JpaRepository<Attempt, String> {

    /**
     * 锁定单条提交行（SELECT ... FOR UPDATE），用于模型复核对同 attempt 的并发串行化。
     *
     * <p>attempts 行是复核写入的唯一天然锁点：复核的幂等闸、sequence 计算与 MODEL 行落库都以它为界串行化，
     * 后到者在锁上等待先到者提交后再读，幂等回读语义不变。只锁已存在的行且 attempt append-only 无删除， 锁等待超时由 InnoDB 默认值兜底；参照 {@code
     * AppUserJpaRepository.lockActiveAdminIds} 先例。
     *
     * @param id 提交 UUID
     * @return 被锁定的提交 id；行不存在时为 null（调用方已先做存在性校验）
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a.id from Attempt a where a.id = :id")
    String lockById(@Param("id") String id);

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

    /**
     * 区间内全部提交（created_at 升序）。
     *
     * @param userId 用户 id
     * @param start 起点
     * @param end 终点
     * @return 提交列表
     */
    List<Attempt> findByUserIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtAsc(
            Long userId, Instant start, Instant end);

    /**
     * 用户全部提交（服务端账号导出用，append-only 全量读取）。
     *
     * @param userId 用户 id
     * @return 提交列表，created_at 升序
     */
    List<Attempt> findByUserIdOrderByCreatedAtAsc(Long userId);

    /**
     * 用户最近提交（时间倒序，取前 10 供 recent 投影裁剪）。
     *
     * @param userId 用户 id
     * @return 提交列表
     */
    List<Attempt> findTop10ByUserIdOrderByCreatedAtDesc(Long userId);
}

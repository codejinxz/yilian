package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.entity.Appeal;
import io.github.codejinxz.yilian.practice.entity.Appeal.AppealKind;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 申诉/不认同 Spring Data 仓库，只允许同包 JpaAppealDao 使用。 */
public interface AppealJpaRepository extends JpaRepository<Appeal, Long> {

    /**
     * 按提交与批注取记录。
     *
     * @param attemptId 提交 id
     * @param annotationKey 批注 key
     * @param kind 类型
     * @return 记录
     */
    Optional<Appeal> findByAttemptIdAndAnnotationKeyAndKind(
            String attemptId, String annotationKey, AppealKind kind);

    /**
     * 用户在题目集合上的指定类型记录。
     *
     * @param userId 用户 id
     * @param kind 类型
     * @param status 状态
     * @param sentenceIds 题目集合
     * @return 记录列表
     */
    List<Appeal> findByUserIdAndKindAndStatusAndSentenceIdIn(
            Long userId, AppealKind kind, String status, Collection<String> sentenceIds);

    /**
     * 某题目上指定类型与状态的记录（判分 overlay 数据源）。
     *
     * @param sentenceId 题目 ULID
     * @param kind 类型
     * @param status 状态
     * @return 记录列表
     */
    List<Appeal> findBySentenceIdAndKindAndStatus(
            String sentenceId, AppealKind kind, String status);

    /**
     * 用户全部指定类型记录。
     *
     * @param userId 用户 id
     * @param kind 类型
     * @param status 状态
     * @return 记录列表
     */
    List<Appeal> findByUserIdAndKindAndStatus(Long userId, AppealKind kind, String status);

    /**
     * 定向 UPDATE 落 LLM 初裁结果：只碰裁决六列，不触碰 status 与提交数据（append-only 纪律—— 判分/提交数据一经落库不改写，裁决列是 appeals
     * 作为「可变投影」的受控 UPDATE 面）。
     *
     * @param id 记录 id
     * @param decision 初裁原文 JSON 或占位
     * @param decisionReason 可展示理由
     * @param decidedBy 裁决者（LLM 或 null）
     * @param decidedAt 裁决时间
     * @param modelSnapshot 模型快照
     * @param promptVersionId prompt 注册表版本 id
     * @return 受影响行数
     */
    @Modifying
    @Query(
            "UPDATE Appeal a"
                    + " SET a.decision = :decision, a.decisionReason = :decisionReason,"
                    + " a.decidedBy = :decidedBy, a.decidedAt = :decidedAt,"
                    + " a.modelSnapshot = :modelSnapshot, a.promptVersionId = :promptVersionId"
                    + " WHERE a.id = :id")
    int updateAdjudication(
            @Param("id") long id,
            @Param("decision") String decision,
            @Param("decisionReason") String decisionReason,
            @Param("decidedBy") String decidedBy,
            @Param("decidedAt") Instant decidedAt,
            @Param("modelSnapshot") String modelSnapshot,
            @Param("promptVersionId") Long promptVersionId);

    /**
     * 定向 UPDATE 落人工终审结果：只碰 status/decision_reason/decided_by/decided_at， decision JSON 保留初裁「AI
     * 意见」不覆盖。
     *
     * @param id 记录 id
     * @param status 终审状态（ACCEPTED / REJECTED）
     * @param decisionReason 终审理由（必填）
     * @param decidedBy 裁决者（恒 ADMIN）
     * @param decidedAt 终审时间
     * @return 受影响行数
     */
    @Modifying
    @Query(
            "UPDATE Appeal a"
                    + " SET a.status = :status, a.decisionReason = :decisionReason,"
                    + " a.decidedBy = :decidedBy, a.decidedAt = :decidedAt"
                    + " WHERE a.id = :id")
    int updateFinalDecision(
            @Param("id") long id,
            @Param("status") String status,
            @Param("decisionReason") String decisionReason,
            @Param("decidedBy") String decidedBy,
            @Param("decidedAt") Instant decidedAt);
}

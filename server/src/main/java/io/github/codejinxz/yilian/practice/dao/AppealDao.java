package io.github.codejinxz.yilian.practice.dao;

import io.github.codejinxz.yilian.practice.entity.Appeal;
import io.github.codejinxz.yilian.practice.entity.Appeal.AppealKind;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 申诉/不认同 Dao：overlay 排除与申诉裁决的数据源。 */
public interface AppealDao {

    /**
     * 保存记录。
     *
     * @param appeal 记录
     * @return 持久化后的记录
     */
    Appeal save(Appeal appeal);

    /**
     * 按提交与批注取记录（uk 唯一性校验）。
     *
     * @param attemptId 提交 id
     * @param annotationKey 批注 key
     * @param kind 类型
     * @return 记录
     */
    Optional<Appeal> findByAttemptAndKey(String attemptId, String annotationKey, AppealKind kind);

    /**
     * 用户在若干题目上的 ACCEPTED DISPUTE（overlay 排除）。
     *
     * @param userId 用户 id
     * @param sentenceIds 题目 id 集合
     * @return 记录列表
     */
    List<Appeal> findDisputed(Long userId, Collection<String> sentenceIds);

    /**
     * 某题目上全部 ACCEPTED 的 APPEAL（判分 overlay：proposed_answer 作为槽位额外可接受答案； DISPUTE 的 ACCEPTED
     * 是排除语义，不进本口径）。
     *
     * @param sentenceId 题目 ULID
     * @return 记录列表
     */
    List<Appeal> findAcceptedAppeals(String sentenceId);

    /**
     * 按 id 取记录。
     *
     * @param id 记录 id
     * @return 记录
     */
    Optional<Appeal> findById(Long id);

    /**
     * 用户全部申诉/不认同记录（服务端账号导出用，append-only 全量读取）。
     *
     * @param userId 用户 id
     * @return 记录列表，created_at 升序
     */
    List<Appeal> findAllByUser(Long userId);

    /**
     * 落 LLM 初裁结果（定向 UPDATE，只碰裁决列：decision/decision_reason/decided_by/decided_at/
     * model_snapshot/prompt_version_id；status 与提交数据不动）。
     *
     * <p>初裁失败/占位路径 decidedBy/decidedAt/快照/版本传 null——「无 AI 意见」读端按 decidedBy 判定。
     *
     * @param id 记录 id
     * @param decision 初裁原文 JSON（或占位 {"verdict":"UNAVAILABLE"}）
     * @param decisionReason 可展示的初裁理由或占位文案
     * @param decidedBy 裁决者（成功恒 LLM；占位为 null）
     * @param decidedAt 裁决时间（占位为 null）
     * @param modelSnapshot 模型快照（占位为 null）
     * @param promptVersionId prompt 注册表版本 id（占位为 null）
     * @return 受影响行数（0=记录不存在或已被并发终审——初裁 UPDATE 的 WHERE 携带 status='PENDING'，
     *     窗口内已终审的行不再落初裁列，调用方按存储真相投影回执）
     */
    int updateAdjudication(
            long id,
            String decision,
            String decisionReason,
            String decidedBy,
            Instant decidedAt,
            String modelSnapshot,
            Long promptVersionId);

    /**
     * 落人工终审结果（定向 UPDATE，只碰 status/decision_reason/decided_by/decided_at； 判分与提交数据不动，decision JSON
     * 保留初裁「AI 意见」）。
     *
     * @param id 记录 id
     * @param status 终审状态（ACCEPTED / REJECTED）
     * @param decisionReason 管理员填写的终审理由（必填）
     * @param decidedAt 终审时间
     * @return 受影响行数（0=记录不存在）
     */
    int updateFinalDecision(long id, String status, String decisionReason, Instant decidedAt);
}

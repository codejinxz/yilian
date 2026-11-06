package io.github.codejinxz.yilian.practice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 判分记录（append-only，attempt_gradings 表）；同一 attempt 按 sequence 递增。
 *
 * <p>result 为判分结果 JSON（schemaVersion=1，与本地引擎 GradingResult 同构）； grader 取 LOCAL（本地判分）或
 * MODEL（模型复核，sequence 2+ 追加， 不覆盖既有行）。MODEL 行的 model_snapshot 记 {@code providerId:model}
 * 快照、latency_ms 记模型调用耗时， 使历史行在模型下线后仍自解释。
 */
@Entity
@Table(name = "attempt_gradings")
public class AttemptGrading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "attempt_id", nullable = false, length = 36)
    private String attemptId;

    @Column(nullable = false)
    private int sequence;

    @Column(nullable = false, length = 16)
    private String grader;

    @Column private Integer score;

    @Column(name = "max_score", nullable = false)
    private int maxScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String result;

    @Column(name = "normalizer_version", nullable = false, length = 16)
    private String normalizerVersion;

    @Column(name = "rubric_version", length = 16)
    private String rubricVersion;

    /** 产生本行判分的模型快照（providerId:model）；本地判分为 null。 */
    @Column(name = "model_snapshot", length = 128)
    private String modelSnapshot;

    /** 模型调用耗时（毫秒，墙钟）；本地判分为 null。 */
    @Column(name = "latency_ms")
    private Integer latencyMs;

    /** 产生本行判分的 prompt 注册表版本 id（prompt_versions.id）；MODEL 复核行落值，本地判分为 null。 */
    @Column(name = "prompt_version_id")
    private Long promptVersionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AttemptGrading() {
        // 供 JPA 反射实例化
    }

    /**
     * 创建本地判分记录。
     *
     * @param attemptId 提交 id
     * @param sequence 判分序号（1 起）
     * @param score 分数（REVEAL 为 null）
     * @param result 判分结果 JSON 文本
     * @param now 服务端时钟
     * @return 未持久化的判分记录
     */
    public static AttemptGrading createLocal(
            String attemptId, int sequence, Integer score, String result, Instant now) {
        AttemptGrading grading = new AttemptGrading();
        grading.attemptId = attemptId;
        grading.sequence = sequence;
        grading.grader = "LOCAL";
        grading.score = score;
        grading.maxScore = 100;
        grading.result = result;
        grading.normalizerVersion = "norm-1";
        grading.rubricVersion = "rubric-1";
        grading.createdAt = now;
        return grading;
    }

    /**
     * 创建模型复核记录（grader=MODEL，append-only 追加行，不覆盖既有判分）。
     *
     * <p>归一化/评分口径版本沿用首判行：复核行的 result JSON 承载的是首判批注结构（分数被复核分替换）， 版本元数据与该 JSON
     * 的产出口径保持一致；prompt_version_id 落 PromptRegistry 取到的 review 版本（注册表缺行时为 null，判分行自解释性降级但不缺行）。
     *
     * @param attemptId 提交 id
     * @param sequence 复核序号（首判 sequence+1）
     * @param score 复核分（0–100，调用方已钳制）
     * @param result 复核结果 JSON 文本（首判批注结构 + 复核分）
     * @param modelSnapshot 模型快照（providerId:model）
     * @param latencyMs 模型调用耗时毫秒
     * @param normalizerVersion 首判行的归一化版本
     * @param rubricVersion 首判行的评分口径版本，可为 null
     * @param promptVersionId prompt 注册表版本 id，可为 null
     * @param now 服务端时钟
     * @return 未持久化的复核记录
     */
    public static AttemptGrading createModelReview(
            String attemptId,
            int sequence,
            Integer score,
            String result,
            String modelSnapshot,
            Integer latencyMs,
            String normalizerVersion,
            String rubricVersion,
            Long promptVersionId,
            Instant now) {
        AttemptGrading grading = new AttemptGrading();
        grading.attemptId = attemptId;
        grading.sequence = sequence;
        grading.grader = "MODEL";
        grading.score = score;
        grading.maxScore = 100;
        grading.result = result;
        grading.normalizerVersion = normalizerVersion;
        grading.rubricVersion = rubricVersion;
        grading.modelSnapshot = modelSnapshot;
        grading.latencyMs = latencyMs;
        grading.promptVersionId = promptVersionId;
        grading.createdAt = now;
        return grading;
    }

    /**
     * 主键。
     *
     * @return 自增 id
     */
    public Long getId() {
        return id;
    }

    /**
     * 所属提交 id。
     *
     * @return 提交 id（UUID）
     */
    public String getAttemptId() {
        return attemptId;
    }

    /**
     * 判分序号。
     *
     * @return 1 起
     */
    public int getSequence() {
        return sequence;
    }

    /**
     * 分数。
     *
     * @return REVEAL 为 null
     */
    public Integer getScore() {
        return score;
    }

    /**
     * 判分结果 JSON 文本。
     *
     * @return JSON
     */
    public String getResult() {
        return result;
    }

    /**
     * 判分器。
     *
     * @return LOCAL
     */
    public String getGrader() {
        return grader;
    }

    /**
     * 归一化版本。
     *
     * @return norm-1
     */
    public String getNormalizerVersion() {
        return normalizerVersion;
    }

    /**
     * 评分口径版本。
     *
     * @return rubric-1
     */
    public String getRubricVersion() {
        return rubricVersion;
    }

    /**
     * 产生本行判分的模型快照。
     *
     * @return providerId:model；本地判分为 null
     */
    public String getModelSnapshot() {
        return modelSnapshot;
    }

    /**
     * 模型调用耗时。
     *
     * @return 毫秒；本地判分为 null
     */
    public Integer getLatencyMs() {
        return latencyMs;
    }

    /**
     * 产生本行判分的 prompt 注册表版本 id。
     *
     * @return prompt_versions.id；本地判分与注册表缺行为 null
     */
    public Long getPromptVersionId() {
        return promptVersionId;
    }

    /**
     * 满分。
     *
     * @return 恒 100
     */
    public int getMaxScore() {
        return maxScore;
    }

    /**
     * 创建时间。
     *
     * @return UTC
     */
    public Instant getCreatedAt() {
        return createdAt;
    }
}

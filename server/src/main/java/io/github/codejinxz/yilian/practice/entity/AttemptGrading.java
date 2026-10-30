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
 * <p>result 为判分结果 JSON（schemaVersion=1，与本地引擎 GradingResult 同构）；
 * grader 本期恒为 LOCAL。
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

    @Column
    private Integer score;

    @Column(name = "max_score", nullable = false)
    private int maxScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String result;

    @Column(name = "normalizer_version", nullable = false, length = 16)
    private String normalizerVersion;

    @Column(name = "rubric_version", length = 16)
    private String rubricVersion;

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
     * 主键。
     *
     * @return 自增 id
     */
    public Long getId() {
        return id;
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

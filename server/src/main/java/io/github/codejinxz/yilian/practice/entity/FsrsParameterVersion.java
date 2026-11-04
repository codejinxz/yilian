package io.github.codejinxz.yilian.practice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * FSRS 参数版本（fsrs_parameter_versions 表）。
 *
 * <p>状态机：CANDIDATE（优化器产出/种子）→ ACTIVE（管理员启用，同 owner 至多一行， 由 uk_fsrs_active_owner 函数索引保证）→ RETIRED（被新
 * ACTIVE 顶替）。weights 原文 JSON 只增不改—— 「改参数 = 新行」，启用/退役只动 status 列。
 *
 * <p>迁移已播种 GLOBAL 原型占位权重行（owner_type=GLOBAL）；优化器产出 GLOBAL CANDIDATE （单人产品语境：优化目标用户的
 * review_logs，但权重行本身是全局的）。
 */
@Entity
@Table(name = "fsrs_parameter_versions")
public class FsrsParameterVersion {

    /** 版本状态：候选。 */
    public static final String STATUS_CANDIDATE = "CANDIDATE";

    /** 版本状态：生效（同 owner 唯一）。 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /** 版本状态：已退役。 */
    public static final String STATUS_RETIRED = "RETIRED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_type", nullable = false, length = 8)
    private String ownerType;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 16)
    private String algorithm;

    @Column(nullable = false, columnDefinition = "JSON")
    private String weights;

    @Column(name = "sample_count", nullable = false)
    private int sampleCount;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(columnDefinition = "JSON")
    private String metrics;

    @Column(name = "optimized_at")
    private Instant optimizedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(length = 255)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FsrsParameterVersion() {
        // 供 JPA 反射实例化
    }

    private FsrsParameterVersion(
            String weights,
            int sampleCount,
            String metrics,
            Long createdBy,
            Instant now,
            String reason) {
        this.ownerType = "GLOBAL";
        this.userId = null;
        this.algorithm = "FSRS-6";
        this.weights = weights;
        this.sampleCount = sampleCount;
        this.status = STATUS_CANDIDATE;
        this.metrics = metrics;
        this.optimizedAt = now;
        this.createdBy = createdBy;
        this.reason = reason;
        this.createdAt = now;
    }

    /**
     * 优化器产出的全局候选权重行。
     *
     * @param weightsJson 21 维 FSRS-6 权重 JSON（DATA-MODEL §6.8）
     * @param sampleCount 训练样本数
     * @param metricsJson 训练指标 JSON（DATA-MODEL §6.9：logLoss/rmse/optimizer/optimizerVersion）
     * @param createdBy 触发优化的管理员 id
     * @param now 服务端时钟
     * @param reason 落库理由（谁在何时优化了谁的数据）
     * @return 未持久化的候选行
     */
    public static FsrsParameterVersion createGlobalCandidate(
            String weightsJson,
            int sampleCount,
            String metricsJson,
            Long createdBy,
            Instant now,
            String reason) {
        return new FsrsParameterVersion(
                weightsJson, sampleCount, metricsJson, createdBy, now, reason);
    }

    /**
     * 状态流转：CANDIDATE → ACTIVE。仅候选可启用（幂等边界在 service 校验）； 旧 ACTIVE 的退役由 Dao 的 bulk UPDATE 即时完成（flush
     * 顺序约束，见 FsrsAdminServiceImpl）。
     */
    public void activate() {
        this.status = STATUS_ACTIVE;
    }

    /**
     * 主键。
     *
     * @return 版本行 id
     */
    public Long getId() {
        return id;
    }

    /**
     * 归属类型。
     *
     * @return GLOBAL/USER
     */
    public String getOwnerType() {
        return ownerType;
    }

    /**
     * 算法标识。
     *
     * @return FSRS-6
     */
    public String getAlgorithm() {
        return algorithm;
    }

    /**
     * 权重 JSON 原文（不改写，解码交给消费方）。
     *
     * @return JSON 字符串
     */
    public String getWeights() {
        return weights;
    }

    /**
     * 训练样本数（种子行为 0）。
     *
     * @return 样本数
     */
    public int getSampleCount() {
        return sampleCount;
    }

    /**
     * 状态。
     *
     * @return CANDIDATE/ACTIVE/RETIRED
     */
    public String getStatus() {
        return status;
    }

    /**
     * 训练指标 JSON 原文（DATA-MODEL §6.9），种子行为 null。
     *
     * @return JSON 字符串，可空
     */
    public String getMetrics() {
        return metrics;
    }

    /**
     * 优化完成时间。
     *
     * @return UTC，种子行为 null
     */
    public Instant getOptimizedAt() {
        return optimizedAt;
    }

    /**
     * 创建者（优化触发的管理员），种子行为 null。
     *
     * @return 用户 id，可空
     */
    public Long getCreatedBy() {
        return createdBy;
    }

    /**
     * 落库理由。
     *
     * @return 理由，可空
     */
    public String getReason() {
        return reason;
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

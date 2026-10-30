package io.github.codejinxz.yilian.practice.entity;

import jakarta.persistence.Column;

import jakarta.persistence.Entity;

import jakarta.persistence.Id;

import jakarta.persistence.Table;

import java.time.Instant;

/**
 * FSRS 参数版本（fsrs_parameter_versions 表）——practice 模块只读投影。
 *
 * <p>参数的生成与启停在 M2.1 优化器切片实现；本期只消费 GLOBAL ACTIVE 行的 id 作为
 * review_logs.weights_version_id 外键（V9 已播种原型占位权重行）。
 */
@Entity
@Table(name = "fsrs_parameter_versions")
public class FsrsParameterVersion {

    @Id
    private Long id;

    @Column(name = "owner_type", nullable = false, length = 8)
    private String ownerType;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected FsrsParameterVersion() {
        // 供 JPA 反射实例化
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
     * 状态。
     *
     * @return CANDIDATE/ACTIVE/RETIRED
     */
    public String getStatus() {
        return status;
    }
}

package io.github.codejinxz.yilian.system.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 系统配置键值（app_settings 表，DATA-MODEL §5.x；V5 迁移建表）。
 *
 * <p>本实体只承担读投影；写入走 {@code AppSettingDao#upsert} 的单语句原生 upsert （INSERT ... ON DUPLICATE KEY
 * UPDATE），并发首写在数据库层原子收敛，不做实体合并。 value 为 JSON 文本，对象内必须带 {@code schemaVersion}（DATA-MODEL §6
 * 约定）；结构由写入方 （当前只有 {@code llm.tutor} 键）自行分支解析。密钥不入库：任何含密钥的配置不得写进本表。
 */
@Entity
@Table(name = "app_settings")
public class AppSetting {

    @Id
    @Column(nullable = false, length = 128)
    private String key;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String value;

    @Column(length = 255)
    private String description;

    @Column(nullable = false)
    private int version;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;

    protected AppSetting() {
        // 供 JPA 反射实例化
    }

    /**
     * 配置键。
     *
     * @return 主键
     */
    public String getKey() {
        return key;
    }

    /**
     * 配置值 JSON 文本。
     *
     * @return JSON
     */
    public String getValue() {
        return value;
    }

    /**
     * 描述。
     *
     * @return 可空
     */
    public String getDescription() {
        return description;
    }

    /**
     * 版本号，每次覆盖递增。
     *
     * @return 1 起
     */
    public int getVersion() {
        return version;
    }

    /**
     * 最近操作者。
     *
     * @return 可空
     */
    public Long getUpdatedBy() {
        return updatedBy;
    }

    /**
     * 最近更新时间。
     *
     * @return UTC
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

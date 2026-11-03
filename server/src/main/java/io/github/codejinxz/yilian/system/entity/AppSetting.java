package io.github.codejinxz.yilian.system.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 系统配置键值（app_settings 表，DATA-MODEL §5.x；V5 迁移建表，本切片首次启用）。
 *
 * <p>value 为 JSON 文本，对象内必须带 {@code schemaVersion}（AGENTS/DATA-MODEL §6 约定）；结构由写入方 （当前只有 017 的
 * {@code llm.tutor} 键）自行分支解析。密钥不入库：任何含密钥的配置不得写进本表（AGENTS §3.4）。
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
     * 新建一条配置；version 从 1 起，并发更新不做乐观锁（单人后台、末写胜出语义，见 AppSettingService）。
     *
     * @param key 配置键（≤128）
     * @param value JSON 文本（含 schemaVersion）
     * @param description 描述，可空
     * @param updatedBy 操作者 id，可空
     * @param now 服务端时钟
     * @return 未持久化的配置行
     */
    public static AppSetting create(
            String key, String value, String description, Long updatedBy, Instant now) {
        AppSetting setting = new AppSetting();
        setting.key = key;
        setting.value = value;
        setting.description = description;
        setting.version = 1;
        setting.updatedBy = updatedBy;
        setting.updatedAt = now;
        return setting;
    }

    /**
     * 覆盖配置值并推进版本与操作者；updated_at 由数据库 ON UPDATE 维护。
     *
     * @param newValue JSON 文本
     * @param by 操作者 id，可空
     */
    public void replaceValue(String newValue, Long by) {
        this.value = newValue;
        this.version = this.version + 1;
        this.updatedBy = by;
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

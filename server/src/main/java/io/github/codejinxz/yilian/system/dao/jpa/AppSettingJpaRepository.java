package io.github.codejinxz.yilian.system.dao.jpa;

import io.github.codejinxz.yilian.system.entity.AppSetting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/** 系统配置 Spring Data 仓库，只允许同包 JpaAppSettingDao 使用。 */
public interface AppSettingJpaRepository extends JpaRepository<AppSetting, String> {

    /**
     * 原生 upsert（MySQL 方言）：无键插入 version=1，撞键覆盖 value/updated_by 并递增 version。
     *
     * <p>覆盖分支刻意不写 updated_at——DDL 的 ON UPDATE CURRENT_TIMESTAMP 会维护它；description
     * 只在首写生效，避免覆盖掉首写时的语义说明。8.0.19+ 行别名写法，VALUES() 在 ODKU 中已弃用。
     *
     * @param key 配置键（主键）
     * @param value JSON 文本
     * @param description 描述（首写生效）
     * @param updatedBy 操作者 id，可空
     * @param updatedAt 插入分支的更新时间（UTC）
     * @return 受影响行数（1=插入或覆盖，0 不应出现）
     */
    @Modifying
    @Query(
            value =
                    "INSERT INTO app_settings (`key`, value, description, version, updated_by, updated_at) "
                            + "VALUES (:key, :value, :description, 1, :updatedBy, :updatedAt) AS new "
                            + "ON DUPLICATE KEY UPDATE value = new.value, version = app_settings.version + 1, "
                            + "updated_by = new.updated_by",
            nativeQuery = true)
    int upsertNative(
            @Param("key") String key,
            @Param("value") String value,
            @Param("description") String description,
            @Param("updatedBy") Long updatedBy,
            @Param("updatedAt") Instant updatedAt);
}

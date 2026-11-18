package io.github.codejinxz.yilian.system.dao;

import io.github.codejinxz.yilian.system.entity.AppSetting;

import java.util.Optional;

/** 系统配置表 Dao：只暴露按键读写，不暴露 delete/deleteAll（配置只增改，不硬删）。 */
public interface AppSettingDao {

    /**
     * 按键查配置。
     *
     * @param key 配置键
     * @return 配置行
     */
    Optional<AppSetting> findByKey(String key);

    /**
     * 原生 upsert：无键插入，有键覆盖 value 并递增 version（末写胜出，单语句原子）。
     *
     * <p>不用「先读后写 + 异常回退」：并发首写撞主键时，同一事务内已标记 rollback-only，回退分支的重读与重写在原事务里根本跑不完（表现为 500）；MySQL 方言的
     * INSERT ... ON DUPLICATE KEY UPDATE 在数据库层一次完成，无竞态窗口。
     *
     * @param key 配置键
     * @param value JSON 文本
     * @param description 首次插入时的描述；覆盖时保留原描述
     * @param updatedBy 操作者 id，可空
     * @param updatedAt 插入分支的更新时间（UTC）；覆盖分支由数据库 ON UPDATE 维护
     */
    void upsert(
            String key,
            String value,
            String description,
            Long updatedBy,
            java.time.Instant updatedAt);
}

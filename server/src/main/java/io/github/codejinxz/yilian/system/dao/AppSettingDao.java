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
     * 保存配置；无键插入，有键覆盖。
     *
     * @param setting 配置实体
     * @return 持久化后的配置
     */
    AppSetting save(AppSetting setting);
}

package io.github.codejinxz.yilian.system.dao.jpa;

import io.github.codejinxz.yilian.system.entity.AppSetting;

import org.springframework.data.jpa.repository.JpaRepository;

/** 系统配置 Spring Data 仓库，只允许同包 JpaAppSettingDao 使用。 */
public interface AppSettingJpaRepository extends JpaRepository<AppSetting, String> {}

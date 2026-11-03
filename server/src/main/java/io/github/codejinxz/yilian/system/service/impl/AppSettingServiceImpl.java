package io.github.codejinxz.yilian.system.service.impl;

import io.github.codejinxz.yilian.system.dao.AppSettingDao;
import io.github.codejinxz.yilian.system.entity.AppSetting;
import io.github.codejinxz.yilian.system.service.AppSettingService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/** 系统配置存取实现：读按主键直查，写在一个事务内收敛「插入/覆盖」两种形态。 */
@Service
public class AppSettingServiceImpl implements AppSettingService {

    private final AppSettingDao appSettingDao;
    private final Clock clock;

    /**
     * 注入依赖并固定 UTC 时钟（与 JwtTokenServiceImpl 同惯例，无独立 Clock bean）。
     *
     * @param appSettingDao 系统配置 Dao
     */
    public AppSettingServiceImpl(AppSettingDao appSettingDao) {
        this.appSettingDao = appSettingDao;
        this.clock = Clock.systemUTC();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findValue(String key) {
        return appSettingDao.findByKey(key).map(AppSetting::getValue);
    }

    @Override
    @Transactional
    public String upsert(String key, String value, String description, Long updatedBy) {
        AppSetting existing = appSettingDao.findByKey(key).orElse(null);
        if (existing == null) {
            return appSettingDao
                    .save(AppSetting.create(key, value, description, updatedBy, clock.instant()))
                    .getValue();
        }
        existing.replaceValue(value, updatedBy);
        return appSettingDao.save(existing).getValue();
    }
}

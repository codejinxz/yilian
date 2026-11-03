package io.github.codejinxz.yilian.system.service.impl;

import io.github.codejinxz.yilian.system.dao.AppSettingDao;
import io.github.codejinxz.yilian.system.entity.AppSetting;
import io.github.codejinxz.yilian.system.service.AppSettingService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;

/** 系统配置存取实现：读按主键直查，写走单语句原生 upsert（并发首写由数据库原子收敛）。 */
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
        // 单语句 upsert（INSERT ... ON DUPLICATE KEY UPDATE）：不做「先读后写」，并发首写无
        // rollback-only 事务可被打断；返回值即写入值，确定性无需回读。
        appSettingDao.upsert(key, value, description, updatedBy, clock.instant());
        return value;
    }
}

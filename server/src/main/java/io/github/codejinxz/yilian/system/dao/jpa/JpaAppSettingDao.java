package io.github.codejinxz.yilian.system.dao.jpa;

import io.github.codejinxz.yilian.system.dao.AppSettingDao;
import io.github.codejinxz.yilian.system.entity.AppSetting;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** AppSettingDao 的 JPA 适配器；insert 并发撞键时收敛为覆盖更新，保证「末写胜出」。 */
@Repository
public class JpaAppSettingDao implements AppSettingDao {

    private final AppSettingJpaRepository repository;

    /**
     * 注入 Spring Data 仓库。
     *
     * @param repository 系统配置仓库
     */
    public JpaAppSettingDao(AppSettingJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<AppSetting> findByKey(String key) {
        return repository.findById(key);
    }

    @Override
    public AppSetting save(AppSetting setting) {
        try {
            // 立即 flush：主键冲突（两个管理员首次并发开启）当场暴露而不是等到事务提交。
            return repository.saveAndFlush(setting);
        } catch (DataIntegrityViolationException exception) {
            // 并发首写同一键：以数据库已存在行为准，把新值覆盖上去（末写胜出）。
            AppSetting existing =
                    repository.findById(setting.getKey()).orElseThrow(() -> exception);
            existing.replaceValue(setting.getValue(), setting.getUpdatedBy());
            return repository.saveAndFlush(existing);
        }
    }
}

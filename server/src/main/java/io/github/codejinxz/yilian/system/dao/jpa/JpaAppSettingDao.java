package io.github.codejinxz.yilian.system.dao.jpa;

import io.github.codejinxz.yilian.system.dao.AppSettingDao;
import io.github.codejinxz.yilian.system.entity.AppSetting;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/** AppSettingDao 的 JPA 适配器；写入走单语句原生 upsert（见 {@link AppSettingJpaRepository#upsertNative}）。 */
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
    public void upsert(
            String key, String value, String description, Long updatedBy, Instant updatedAt) {
        // 单语句原子：并发首写/覆盖都在数据库层收敛，无「先读后写」的竞态窗口，也无需异常回退事务。
        repository.upsertNative(key, value, description, updatedBy, updatedAt);
    }
}

package io.github.codejinxz.yilian.system.dao.jpa;

import io.github.codejinxz.yilian.system.dao.User2faRecoveryCodeDao;
import io.github.codejinxz.yilian.system.entity.User2faRecoveryCode;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** User2faRecoveryCodeDao 的 JPA 适配器。 */
@Repository
public class JpaUser2faRecoveryCodeDao implements User2faRecoveryCodeDao {

    private final User2faRecoveryCodeJpaRepository repository;

    /**
     * 注入 Spring Data 仓库。
     *
     * @param repository 恢复码仓库
     */
    public JpaUser2faRecoveryCodeDao(User2faRecoveryCodeJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public User2faRecoveryCode save(User2faRecoveryCode code) {
        return repository.save(code);
    }

    @Override
    public List<User2faRecoveryCode> findUnusedByUserId(long userId) {
        return repository.findByUserIdAndUsedAtIsNullOrderByIdAsc(userId);
    }

    @Override
    public boolean markUsedIfUnused(long userId, long codeId, Instant now) {
        // 影响行数 >0 才算消费成功；0=已被并发消费或行不存在，由服务层收敛为 40101。
        return repository.markUsedIfUnused(codeId, userId, now) > 0;
    }

    @Override
    public long deleteByUserId(long userId) {
        return repository.deleteByUserId(userId);
    }
}

package io.github.codejinxz.yilian.system.dao.jpa;

import io.github.codejinxz.yilian.system.dao.AppUserDao;
import io.github.codejinxz.yilian.system.entity.AppUser;
import io.github.codejinxz.yilian.system.entity.UserRole;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** AppUserDao 的 JPA 适配器，把仓库能力收窄为账号业务需要的方法。 */
@Repository
public class JpaAppUserDao implements AppUserDao {

    private final AppUserJpaRepository repository;

    /**
     * 注入 Spring Data 仓库。
     *
     * @param repository 账号仓库
     */
    public JpaAppUserDao(AppUserJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public AppUser save(AppUser user) {
        // 立即 flush：唯一键冲突在服务方法内暴露，便于映射成 409 业务码而不是延迟到提交阶段。
        return repository.saveAndFlush(user);
    }

    @Override
    public Optional<AppUser> findById(Long id) {
        return repository.findById(id);
    }

    @Override
    public List<AppUser> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return repository.findAllById(ids);
    }

    @Override
    public Optional<AppUser> findByUsername(String username) {
        return repository.findByUsername(username);
    }

    @Override
    public Optional<AppUser> findByEmail(String email) {
        return repository.findByEmail(email);
    }

    @Override
    public Optional<AppUser> findByLogin(String login) {
        return repository.findByUsernameOrEmail(login, login);
    }

    @Override
    public boolean existsByUsername(String username) {
        return repository.existsByUsername(username);
    }

    @Override
    public boolean existsByEmail(String email) {
        return repository.existsByEmail(email);
    }

    @Override
    public long countActiveAdminsForUpdate() {
        // 锁定全部管理员行后按行数计数：count 聚合不可 FOR UPDATE，
        // 行锁把「读-判-写」串行化，并发降级不可能同时读到过期计数。
        return repository.lockActiveAdminIds(UserRole.ADMIN).size();
    }

    @Override
    public Page<AppUser> search(String keyword, UserRole role, String status, Pageable pageable) {
        return repository.search(keyword, role, status, pageable);
    }

    @Override
    public List<AppUser> findAnonymizationDue(Instant cutoff, Pageable pageable) {
        return repository.findByDeletedAtLessThanEqualAndAnonymizedAtIsNullOrderByDeletedAtAsc(
                cutoff, pageable);
    }
}

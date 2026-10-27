package io.github.codejinxz.yilian.system.dao.jpa;

import io.github.codejinxz.yilian.system.dao.InviteCodeDao;
import io.github.codejinxz.yilian.system.entity.InviteCode;

import org.springframework.stereotype.Repository;

import java.util.Optional;

/** InviteCodeDao 的 JPA 适配器。 */
@Repository
public class JpaInviteCodeDao implements InviteCodeDao {

    private final InviteCodeJpaRepository repository;

    /**
     * 注入 Spring Data 仓库。
     *
     * @param repository 邀请码仓库
     */
    public JpaInviteCodeDao(InviteCodeJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<InviteCode> findByCodeHash(String codeHash) {
        return repository.findByCodeHash(codeHash);
    }

    @Override
    public InviteCode save(InviteCode inviteCode) {
        return repository.save(inviteCode);
    }
}

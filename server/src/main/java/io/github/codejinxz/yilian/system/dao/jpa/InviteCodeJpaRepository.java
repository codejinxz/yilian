package io.github.codejinxz.yilian.system.dao.jpa;

import io.github.codejinxz.yilian.system.entity.InviteCode;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 邀请码 Spring Data 仓库，只允许同包 JpaInviteCodeDao 使用。 */
public interface InviteCodeJpaRepository extends JpaRepository<InviteCode, Long> {

    /**
     * 按哈希查邀请码。
     *
     * @param codeHash SHA-256 十六进制
     * @return 邀请码实体
     */
    Optional<InviteCode> findByCodeHash(String codeHash);
}

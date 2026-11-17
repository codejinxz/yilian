package io.github.codejinxz.yilian.system.dao.jpa;

import io.github.codejinxz.yilian.system.entity.User2faRecoveryCode;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 2FA 恢复码 Spring Data 仓库，只允许同包 JpaUser2faRecoveryCodeDao 使用。 */
public interface User2faRecoveryCodeJpaRepository extends JpaRepository<User2faRecoveryCode, Long> {

    /**
     * 取某用户全部未使用的恢复码行。
     *
     * @param userId 用户 id
     * @return 未使用行（id 升序）
     */
    List<User2faRecoveryCode> findByUserIdAndUsedAtIsNullOrderByIdAsc(long userId);

    /**
     * 删除某用户全部恢复码行（派生删除按行加载后逐行删，返回删除数）；恢复码是凭据投影， 允许物理删除（与答题/审计 append-only 纪律无关）。
     *
     * @param userId 用户 id
     * @return 删除行数
     */
    long deleteByUserId(long userId);
}

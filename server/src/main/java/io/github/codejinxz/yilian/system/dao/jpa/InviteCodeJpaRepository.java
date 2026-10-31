package io.github.codejinxz.yilian.system.dao.jpa;

import io.github.codejinxz.yilian.system.entity.InviteCode;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;

/** 邀请码 Spring Data 仓库，只允许同包 JpaInviteCodeDao 使用。 */
public interface InviteCodeJpaRepository extends JpaRepository<InviteCode, Long> {

    /**
     * 按哈希查邀请码；行级悲观锁串行化注册事务中的核销判定， 消除「读→校验→核销」的 check-then-act 竞态（唯一约束 uk_invite_used_by 兜底）。
     *
     * @param codeHash SHA-256 十六进制
     * @return 邀请码实体
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<InviteCode> findByCodeHash(String codeHash);

    /**
     * 按分页取未软删邀请码，排序交给 Pageable；管理端列表用（最近优先、限量）。
     *
     * @param pageable 分页与排序
     * @return 邀请码实体列表
     */
    List<InviteCode> findByDeletedAtIsNull(Pageable pageable);
}

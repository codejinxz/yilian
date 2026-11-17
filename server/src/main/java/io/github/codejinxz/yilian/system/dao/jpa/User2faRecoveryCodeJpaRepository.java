package io.github.codejinxz.yilian.system.dao.jpa;

import io.github.codejinxz.yilian.system.entity.User2faRecoveryCode;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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
     * 条件更新式原子消费：仅当 {@code used_at IS NULL} 时落值。WHERE 条件把「是否已被并发
     * 消费」下沉到数据库行级原子性——应用层「查未用→标记」两步之间的竞态窗口由此消除， 后到的影响行数为 0，单次语义不依赖任何应用层锁。
     *
     * @param codeId 恢复码行 id
     * @param userId 归属用户 id（WHERE 内一并校验，防跨用户消费）
     * @param usedAt 使用时刻
     * @return 影响行数：1=本次消费成功；0=已被并发消费或行不存在
     */
    @Modifying
    @Query(
            "UPDATE User2faRecoveryCode c SET c.usedAt = :usedAt"
                    + " WHERE c.id = :codeId AND c.userId = :userId AND c.usedAt IS NULL")
    int markUsedIfUnused(
            @Param("codeId") long codeId,
            @Param("userId") long userId,
            @Param("usedAt") Instant usedAt);

    /**
     * 删除某用户全部恢复码行（派生删除按行加载后逐行删，返回删除数）；恢复码是凭据投影， 允许物理删除（与答题/审计 append-only 纪律无关）。
     *
     * @param userId 用户 id
     * @return 删除行数
     */
    long deleteByUserId(long userId);
}

package io.github.codejinxz.yilian.system.dao;

import io.github.codejinxz.yilian.system.entity.InviteCode;

import java.util.Optional;

/** 邀请码 Dao。 */
public interface InviteCodeDao {

    /**
     * 按哈希查邀请码。
     *
     * @param codeHash SHA-256 十六进制
     * @return 邀请码实体
     */
    Optional<InviteCode> findByCodeHash(String codeHash);

    /**
     * 保存邀请码。
     *
     * @param inviteCode 邀请码实体
     * @return 持久化后的实体
     */
    InviteCode save(InviteCode inviteCode);
}

package io.github.codejinxz.yilian.system.dao;

import io.github.codejinxz.yilian.system.entity.InviteCode;

import java.util.List;
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
     * 取最近创建的未软删邀请码，按创建时间倒序（同秒再按 id 倒序保证稳定）。
     *
     * @param limit 最多返回条数，必须为正
     * @return 邀请码实体列表
     */
    List<InviteCode> findRecent(int limit);

    /**
     * 保存邀请码。
     *
     * @param inviteCode 邀请码实体
     * @return 持久化后的实体
     */
    InviteCode save(InviteCode inviteCode);
}

package io.github.codejinxz.yilian.system.dao;

import io.github.codejinxz.yilian.system.entity.User2faRecoveryCode;

import java.util.List;

/** 2FA 恢复码表 Dao：只暴露业务需要的单表操作，删除限定在单用户范围内。 */
public interface User2faRecoveryCodeDao {

    /**
     * 保存恢复码行。
     *
     * @param code 恢复码实体
     * @return 持久化后的实体
     */
    User2faRecoveryCode save(User2faRecoveryCode code);

    /**
     * 取某用户全部未使用的恢复码行（BCrypt 逐条比对由服务层完成，本层不做哈希判断）。
     *
     * @param userId 用户 id
     * @return 未使用行（按 id 升序，顺序稳定便于测试）
     */
    List<User2faRecoveryCode> findUnusedByUserId(long userId);

    /**
     * 删除某用户全部恢复码行：disable / reset-2fa 清 enrollment 时调用（凭据投影删除， 非历史抹除），必须与 users 三列清空同事务。
     *
     * @param userId 用户 id
     * @return 删除的行数
     */
    long deleteByUserId(long userId);
}

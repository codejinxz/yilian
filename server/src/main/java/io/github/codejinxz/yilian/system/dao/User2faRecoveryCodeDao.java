package io.github.codejinxz.yilian.system.dao;

import io.github.codejinxz.yilian.system.entity.User2faRecoveryCode;

import java.time.Instant;
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
     * 原子消费恢复码：仅当行仍未使用时落 {@code used_at}（条件更新，数据库行级原子性保证）。
     *
     * <p>单次语义由这条 UPDATE 的 WHERE 条件表达——两笔并发消费同一码时，后到者因 {@code used_at IS NULL} 不再命中而影响行数为
     * 0，不存在「查未用→标记」的应用层竞态窗口。
     *
     * @param userId 用户 id（行归属校验，防跨用户消费）
     * @param codeId 恢复码行 id
     * @param now 使用时刻
     * @return 消费成功（影响行数 1）为 true；已被并发消费或行不存在（影响行数 0）为 false
     */
    boolean markUsedIfUnused(long userId, long codeId, Instant now);

    /**
     * 删除某用户全部恢复码行：disable / reset-2fa 清 enrollment 时调用（凭据投影删除， 非历史抹除），必须与 users 三列清空同事务。
     *
     * @param userId 用户 id
     * @return 删除的行数
     */
    long deleteByUserId(long userId);
}

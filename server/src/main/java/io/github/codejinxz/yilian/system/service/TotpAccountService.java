package io.github.codejinxz.yilian.system.service;

import io.github.codejinxz.yilian.system.service.dto.TotpAccountView;

import java.util.List;

/**
 * 用户 2FA 账户用例（system 域）：users 表 TOTP 三列与 user_2fa_recovery_codes 表的 原子状态迁移。
 *
 * <p>TOTP 算法与密钥生成在 auth 模块（{@code TotpService}），本接口只承接已计算好的密文 与哈希做落库与读取——加密、验码编排不在这里。跨模块调用方
 * （auth）只依赖本接口。
 */
public interface TotpAccountService {

    /**
     * 读取用户 2FA 账户状态（密文、开关、剩余恢复码与未用码哈希）。
     *
     * @param userId 用户 id
     * @return 状态视图；用户不存在时为空
     */
    java.util.Optional<TotpAccountView> findAccount(long userId);

    /**
     * 两步验证第一步（setup）：落新密文，启用开关不动；重复调用覆盖旧密文（旧验证器绑定 密钥即作废）。恢复码不动。
     *
     * @param userId 用户 id
     * @param secretCipher 新密钥的 AES-256-GCM 密文（调用方已加密）
     */
    void stageTotpSecret(long userId, String secretCipher);

    /**
     * 两步验证第二步（activate）：绑定密文、置启用开关与启用时刻，并整体重发恢复码 —— 先删旧恢复码行再插入新哈希，明文由调用方生成后只回传一次。
     *
     * @param userId 用户 id
     * @param expectedCipher 激活时校验的密文（防止并发 setup 把验证过的密钥换掉）
     * @param recoveryCodeHashes 新恢复码的 BCrypt 哈希（调用方生成明文并加密后传入）
     */
    void activateTotp(long userId, String expectedCipher, List<String> recoveryCodeHashes);

    /**
     * 清空两步验证注册：users 三列清回未注册态并删除全部恢复码行（凭据投影删除， 必须与本清空同事务——残留旧码在重新注册后仍可登录）。
     *
     * @param userId 用户 id
     */
    void clearTotpEnrollment(long userId);

    /**
     * 标记恢复码已使用（落 used_at，append-only：不删行、不重置）。
     *
     * @param userId 用户 id（行归属校验，防跨用户消费）
     * @param codeId 恢复码行 id
     */
    void markRecoveryCodeUsed(long userId, long codeId);
}

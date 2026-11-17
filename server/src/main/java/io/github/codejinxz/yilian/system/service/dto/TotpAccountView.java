package io.github.codejinxz.yilian.system.service.dto;

import java.time.Instant;
import java.util.List;

/**
 * 用户 2FA 账户状态的跨模块只读视图（system → auth）：认证编排（登录第二步、enrollment） 需要 users 表的 TOTP 列与恢复码行，但 auth 模块不接触
 * system 的 dao/entity，全部经本视图传递。
 *
 * <p>{@code secretCipher} 是 AES-256-GCM 密文（非明文），auth 侧只把它交给解密与 TOTP 校验链路，不落日志、不回显；恢复码只传 BCrypt 哈希与行
 * id（与密码哈希同纪律：哈希仅 在认证服务内部用于比对）。
 *
 * @param userId 用户 id
 * @param username 登录名（日志串联与 otpauth label 用）
 * @param totpEnabled 是否已启用两步验证
 * @param enrolledAt 启用时刻；未启用为 null
 * @param secretCipher TOTP 密钥密文；未 setup 时为 null
 * @param remainingRecoveryCodes 剩余可用恢复码数
 * @param unusedRecoveryCodes 未使用恢复码行（id + BCrypt 哈希），登录第二步恢复码比对用
 */
public record TotpAccountView(
        long userId,
        String username,
        boolean totpEnabled,
        Instant enrolledAt,
        String secretCipher,
        int remainingRecoveryCodes,
        List<RecoveryCodeHash> unusedRecoveryCodes) {

    /**
     * 未使用恢复码行投影。
     *
     * @param id 恢复码行 id（消费后按 id 标记 used_at）
     * @param codeHash BCrypt 哈希（逐条比对用）
     */
    public record RecoveryCodeHash(long id, String codeHash) {}
}

package io.github.codejinxz.yilian.system.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 2FA 一次性恢复码实体，对应 {@code user_2fa_recovery_codes} 表。
 *
 * <p>库内只存 BCrypt 哈希，明文只在激活响应里出现一次；使用即落 {@code used_at} （append-only 语义：不删行、不重置，审计可回放）。
 * 行不是答题/审计类历史，而是凭据投影：关闭 2FA 或管理员重置时由服务层在同一事务内删行—— 残留旧码在重新注册后仍可登录，保留即安全漏洞。 零物理外键：{@code userId} 逻辑引用
 * {@code users.id}，级联清理由应用层保证。
 */
@Entity
@Table(name = "user_2fa_recovery_codes")
public class User2faRecoveryCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "code_hash", nullable = false, length = 255)
    private String codeHash;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected User2faRecoveryCode() {
        // 供 JPA 反射实例化，业务代码请用静态工厂
    }

    /**
     * 创建未使用的恢复码行。
     *
     * @param userId 归属用户 id
     * @param codeHash 恢复码的 BCrypt 哈希
     * @param now 创建时间
     * @return 未持久化实体
     */
    public static User2faRecoveryCode unused(Long userId, String codeHash, Instant now) {
        User2faRecoveryCode code = new User2faRecoveryCode();
        code.userId = userId;
        code.codeHash = codeHash;
        code.createdAt = now;
        return code;
    }

    /**
     * 标记本恢复码已被使用：只落 {@code used_at}，不删行不重置—— 用过即废的一次性语义由该列表达，审计可回放。
     *
     * @param now 使用时间
     */
    public void markUsed(Instant now) {
        this.usedAt = now;
    }

    /**
     * 主键。
     *
     * @return 自增 id，未持久化时为 null
     */
    public Long getId() {
        return id;
    }

    /**
     * 归属用户 id。
     *
     * @return 逻辑引用 users.id
     */
    public Long getUserId() {
        return userId;
    }

    /**
     * 恢复码哈希。
     *
     * @return BCrypt 哈希，不应出现在日志或响应中
     */
    public String getCodeHash() {
        return codeHash;
    }

    /**
     * 使用时刻。
     *
     * @return UTC 时间；未使用时为 null
     */
    public Instant getUsedAt() {
        return usedAt;
    }

    /**
     * 创建时刻。
     *
     * @return UTC 时间戳
     */
    public Instant getCreatedAt() {
        return createdAt;
    }
}

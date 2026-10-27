package io.github.codejinxz.yilian.system.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 邀请码实体，对应 {@code invite_codes} 表。
 *
 * <p>只存规范化邀请码的 SHA-256 与前缀，不存明文；一码一人时 {@code usedBy} 唯一。注册在事务内锁行校验并回写使用信息。
 */
@Entity
@Table(name = "invite_codes")
public class InviteCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code_hash", nullable = false, unique = true, length = 64)
    private String codeHash;

    @Column(name = "code_prefix", nullable = false, length = 12)
    private String codePrefix;

    @Column(name = "max_uses", nullable = false)
    private short maxUses;

    @Column(name = "used_count", nullable = false)
    private short usedCount;

    @Column(name = "used_by")
    private Long usedBy;

    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private Long deletedBy;

    @Column(name = "revoked_reason", length = 255)
    private String revokedReason;

    protected InviteCode() {
        // JPA
    }

    /**
     * 创建一次性邀请码。
     *
     * @param codeHash 规范化邀请码的 SHA-256
     * @param codePrefix 展示前缀
     * @param expiresAt 过期时间，可为 null
     * @param createdBy 创建管理员，可为 null
     * @param now 创建时间
     * @return 未持久化实体
     */
    public static InviteCode newSingleUse(
            String codeHash, String codePrefix, Instant expiresAt, Long createdBy, Instant now) {
        InviteCode code = new InviteCode();
        code.codeHash = codeHash;
        code.codePrefix = codePrefix;
        code.maxUses = 1;
        code.usedCount = 0;
        code.expiresAt = expiresAt;
        code.enabled = true;
        code.createdBy = createdBy;
        code.createdAt = now;
        return code;
    }

    /**
     * 当前是否可用于注册。
     *
     * @param now 当前时间
     * @return 启用、未删、未过期且仍有次数时返回 true
     */
    public boolean usableAt(Instant now) {
        return enabled
                && deletedAt == null
                && usedCount < maxUses
                && (expiresAt == null || expiresAt.isAfter(now));
    }

    /**
     * 标记一次使用；一码一人时同时写 usedBy/usedAt。
     *
     * @param userId 使用者 id
     * @param now 使用时间
     */
    public void markUsed(long userId, Instant now) {
        this.usedCount++;
        this.usedBy = userId;
        this.usedAt = now;
    }

    /**
     * 主键。
     *
     * @return 自增 id
     */
    public Long getId() {
        return id;
    }

    /**
     * 哈希。
     *
     * @return SHA-256 十六进制
     */
    public String getCodeHash() {
        return codeHash;
    }

    /**
     * 展示前缀。
     *
     * @return 前缀，如 YL-AB12
     */
    public String getCodePrefix() {
        return codePrefix;
    }

    /**
     * 是否启用。
     *
     * @return 启用状态
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 已使用次数。
     *
     * @return 使用次数
     */
    public short getUsedCount() {
        return usedCount;
    }

    /**
     * 使用者。
     *
     * @return 用户 id；未使用时为 null
     */
    public Long getUsedBy() {
        return usedBy;
    }
}

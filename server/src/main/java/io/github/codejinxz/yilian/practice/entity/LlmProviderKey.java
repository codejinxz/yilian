package io.github.codejinxz.yilian.practice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * provider 目录条目的入库密钥行（llm_provider_keys 表，DATA-MODEL §5.20）：管理端可 把条目 API Key 以 AES-256-GCM
 * 密文形态落本表，主密钥只走环境变量（不入库）。
 *
 * <p>与 {@code prompt_versions} 这类 append-only 快照不同，<strong>本表是密钥投影表</strong>： 录入即 upsert（同
 * provider_id 覆盖旧密文）、目录条目删除即删行（与 app_settings 目录写同一事务， 零物理外键下的一致性由应用层保证）。{@code secretCipher}
 * 列任何路径都不回读为明文——明文只在 加密前的内存短暂存在，不入日志/审计/响应；{@code keyLast4} 是录入时留痕的尾 4 位辨识号， 供管理员辨认密钥，不算明文泄露。
 */
@Entity
@Table(name = "llm_provider_keys")
public class LlmProviderKey {

    /** provider 目录条目 id；逻辑引用（目录存 app_settings JSON，无法建物理外键）。 */
    @Id
    @Column(name = "provider_id", nullable = false, length = 64)
    private String providerId;

    /** AES-256-GCM 密文：Base64(12 字节随机 IV ‖ 密文含 auth tag)；列上无明文。 */
    @Column(name = "secret_cipher", nullable = false, columnDefinition = "TEXT")
    private String secretCipher;

    /** 录入时留痕的尾 4 位辨识号；env 态无本行，此列为空。 */
    @Column(name = "key_last4", length = 4)
    private String keyLast4;

    /** 最近一次写入时间（UTC）；upsert 与目录删除同事务变更。 */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LlmProviderKey() {
        // 供 JPA 反射实例化
    }

    /**
     * 全字段构造（密钥行由 Dao upsert 写入，字段含义见类注释）。
     *
     * @param providerId provider 目录条目 id
     * @param secretCipher AES-256-GCM 密文（Base64(IV ‖ 密文+tag)）
     * @param keyLast4 尾 4 位辨识号（可空）
     * @param updatedAt 最近写入时间（UTC）
     */
    public LlmProviderKey(
            String providerId, String secretCipher, String keyLast4, Instant updatedAt) {
        this.providerId = providerId;
        this.secretCipher = secretCipher;
        this.keyLast4 = keyLast4;
        this.updatedAt = updatedAt;
    }

    /**
     * provider 目录条目 id。
     *
     * @return 条目 id（主键）
     */
    public String getProviderId() {
        return providerId;
    }

    /**
     * 密文。
     *
     * @return Base64(IV ‖ 密文+tag)；解密只经 {@code ProviderKeyCrypto}
     */
    public String getSecretCipher() {
        return secretCipher;
    }

    /**
     * 尾 4 位辨识号。
     *
     * @return 录入时留痕的 last4；可空
     */
    public String getKeyLast4() {
        return keyLast4;
    }

    /**
     * 最近写入时间。
     *
     * @return UTC 时间
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * 覆盖密文与辨识号（upsert 语义：同一 provider_id 只保留最新一把）。
     *
     * @param secretCipher 新密文
     * @param keyLast4 新尾 4 位辨识号
     * @param updatedAt 本次写入时间（UTC）
     */
    public void applyUpdate(String secretCipher, String keyLast4, Instant updatedAt) {
        this.secretCipher = secretCipher;
        this.keyLast4 = keyLast4;
        this.updatedAt = updatedAt;
    }
}

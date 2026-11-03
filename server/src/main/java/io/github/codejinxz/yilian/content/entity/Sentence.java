package io.github.codejinxz.yilian.content.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 题目身份实体，对应 {@code sentences} 表。
 *
 * <p>内容在 {@link SentenceVersion} 中不可变；本表只维护当前版本指针、生命周期与银行级修订号。 删除/停用必须 bump {@code
 * version}，否则增量客户端看不到 tombstone。
 */
@Entity
@Table(name = "sentences")
public class Sentence {

    @Id
    @Column(length = 26)
    private String id;

    @Column(name = "bank_id", nullable = false)
    private Long bankId;

    @Column(name = "external_key", length = 64)
    private String externalKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ContentStatus status;

    @Column(name = "current_version_id")
    private Long currentVersionId;

    @Column(nullable = false)
    private long version;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deleted_by")
    private Long deletedBy;

    protected Sentence() {
        // 供 JPA 反射实例化，业务代码请用静态工厂
    }

    /**
     * 新建题目身份；当前版本指针在版本行插入后同事务回填。
     *
     * @param id ULID
     * @param bankId 所属题库
     * @param externalKey 导入源 id，可为 null
     * @param fingerprint 内容指纹
     * @param actorId 创建者
     * @param now 创建时间
     * @return 未持久化实体
     */
    public static Sentence create(
            String id,
            long bankId,
            String externalKey,
            String fingerprint,
            Long actorId,
            Instant now) {
        Sentence sentence = new Sentence();
        sentence.id = id;
        sentence.bankId = bankId;
        sentence.externalKey = externalKey;
        sentence.status = ContentStatus.DRAFT;
        sentence.version = 0;
        sentence.fingerprint = fingerprint;
        sentence.createdBy = actorId;
        sentence.updatedBy = actorId;
        sentence.createdAt = now;
        sentence.updatedAt = now;
        return sentence;
    }

    /**
     * 指向新的内容快照，并推进到银行级修订号；不改生命周期状态。
     *
     * <p>编辑草稿题不再隐式发布（016 P2·02#03 裁决）：题目五态生命周期（DRAFT→PUBLISHED→…） 必须显式流转，编辑只换内容指针，状态保持原样——
     * 否则「先降草稿、改好再发」的管理动作会被一次行内编辑提前上线。 导入落库的首次发布由 {@code ContentImportServiceImpl.importSentences}
     * 在指向快照后显式调用 {@link #changeStatus} 完成。
     *
     * @param versionId 新版本行 id
     * @param versionNo 单题版本号，仅用于断言
     * @param bankVersion 新的银行级修订号
     * @param fingerprint 新内容指纹
     * @param actorId 操作者
     */
    public void pointToVersion(
            long versionId, int versionNo, long bankVersion, String fingerprint, Long actorId) {
        this.currentVersionId = versionId;
        this.version = bankVersion;
        this.fingerprint = fingerprint;
        this.updatedBy = actorId;
        touch();
    }

    /**
     * 变更生命周期并 bump 版本；进回收站写 deleted_at，移出回收站清软删标记。
     *
     * <p>「回收站 = RECYCLED + 非空 deleted_at」（ContentStatus 约定）：恢复/启用若不清 deleted_at， 详情与增量流仍会按
     * tombstone 处理，管理端「恢复」就永远不生效。
     *
     * @param status 新状态
     * @param bankVersion 新银行级修订号
     * @param actorId 操作者
     * @param now 操作时间
     */
    public void changeStatus(ContentStatus status, long bankVersion, Long actorId, Instant now) {
        this.status = status;
        this.version = bankVersion;
        if (status == ContentStatus.RECYCLED) {
            this.deletedAt = now;
            this.deletedBy = actorId;
        } else if (this.deletedAt != null) {
            this.deletedAt = null;
            this.deletedBy = null;
        }
        this.updatedBy = actorId;
        touch();
    }

    /**
     * 批量移动到另一题库，并推进到目标题库的新修订号。
     *
     * <p>只改 {@code sentences.bank_id} 定位列；历史内容快照（sentence_versions.bank_id）不可变， 管理端列表一律经 {@code
     * current_version_id} JOIN 取内容，不依赖快照上的题库冗余列。
     *
     * @param targetBankId 目标题库 id
     * @param bankVersion 目标题库本次移动后的修订号
     * @param actorId 操作者
     */
    public void moveToBank(long targetBankId, long bankVersion, Long actorId) {
        this.bankId = targetBankId;
        this.version = bankVersion;
        this.updatedBy = actorId;
        touch();
    }

    /**
     * 更新内容指纹，供导入去重使用。
     *
     * @param fingerprint 新的内容指纹（SHA-256 十六进制）
     */
    public void updateFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    /**
     * ULID。
     *
     * @return 题目 id
     */
    public String getId() {
        return id;
    }

    /**
     * 所属题库。
     *
     * @return 题库 id
     */
    public Long getBankId() {
        return bankId;
    }

    /**
     * 导入源 id。
     *
     * @return 外部键，可为 null
     */
    public String getExternalKey() {
        return externalKey;
    }

    /**
     * 生命周期。
     *
     * @return 状态
     */
    public ContentStatus getStatus() {
        return status;
    }

    /**
     * 当前版本行 id。
     *
     * @return {@code sentence_versions.id}
     */
    public Long getCurrentVersionId() {
        return currentVersionId;
    }

    /**
     * 银行级修订号。
     *
     * @return 版本
     */
    public long getVersion() {
        return version;
    }

    /**
     * 内容指纹。
     *
     * @return SHA-256
     */
    public String getFingerprint() {
        return fingerprint;
    }

    /**
     * 软删时间。
     *
     * @return UTC 时间
     */
    public Instant getDeletedAt() {
        return deletedAt;
    }

    /**
     * 创建时间。
     *
     * @return UTC 时间
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * 更新时间。
     *
     * @return UTC 时间
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

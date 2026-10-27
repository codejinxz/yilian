package io.github.codejinxz.yilian.content.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 题库实体，对应 {@code sentence_banks} 表。
 *
 * <p>{@code version} 是银行级修订号：发布、改题、删题、恢复都会 +1，客户端按它做增量拉取与 tombstone 识别。
 */
@Entity
@Table(name = "sentence_banks")
public class SentenceBank {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BankVisibility visibility;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ContentStatus status;

    @Column(nullable = false)
    private long version;

    @Column(name = "published_at")
    private Instant publishedAt;

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

    protected SentenceBank() {
        // JPA
    }

    /**
     * 新建草稿题库。
     *
     * @param ownerId 所有者；null 表示官方题库
     * @param name 名称
     * @param description 说明
     * @param visibility 可见性
     * @param actorId 创建者
     * @param now 创建时间
     * @return 未持久化实体
     */
    public static SentenceBank create(
            Long ownerId,
            String name,
            String description,
            BankVisibility visibility,
            Long actorId,
            Instant now) {
        SentenceBank bank = new SentenceBank();
        bank.ownerId = ownerId;
        bank.name = name;
        bank.description = description;
        bank.visibility = visibility;
        bank.status = ContentStatus.DRAFT;
        bank.version = 0;
        bank.createdBy = actorId;
        bank.updatedBy = actorId;
        bank.createdAt = now;
        bank.updatedAt = now;
        return bank;
    }

    /**
     * 修改基础信息。
     *
     * @param name 新名称，null 表示不改
     * @param description 新说明，null 表示不改
     * @param visibility 新可见性，null 表示不改
     * @param actorId 操作者
     */
    public void update(String name, String description, BankVisibility visibility, Long actorId) {
        if (name != null) {
            this.name = name;
        }
        if (description != null) {
            this.description = description;
        }
        if (visibility != null) {
            this.visibility = visibility;
        }
        this.updatedBy = actorId;
        touch();
    }

    /**
     * 变更生命周期；发布时记录首次/最近发布时间。
     *
     * @param status 新状态
     * @param actorId 操作者
     * @param now 操作时间
     */
    public void changeStatus(ContentStatus status, Long actorId, Instant now) {
        this.status = status;
        if (status == ContentStatus.PUBLISHED && this.publishedAt == null) {
            this.publishedAt = now;
        }
        if (status == ContentStatus.RECYCLED) {
            this.deletedAt = now;
            this.deletedBy = actorId;
        }
        this.updatedBy = actorId;
        touch();
    }

    /**
     * 软删进回收站；不硬删题目。
     *
     * @param actorId 操作者
     * @param now 删除时间
     */
    public void recycle(Long actorId, Instant now) {
        this.status = ContentStatus.RECYCLED;
        this.deletedAt = now;
        this.deletedBy = actorId;
        this.updatedBy = actorId;
        touch();
    }

    /** 题目增删改后推进银行级修订号。 */
    public void bumpVersion() {
        this.version++;
        touch();
    }

    /**
     * 导入提交时统一推进到新修订号。
     *
     * @param newVersion 新修订号
     * @param actorId 操作者
     * @param now 操作时间
     */
    public void advanceVersion(long newVersion, Long actorId, Instant now) {
        this.version = newVersion;
        if (this.status == ContentStatus.DRAFT) {
            this.status = ContentStatus.PUBLISHED;
            this.publishedAt = now;
        }
        this.updatedBy = actorId;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
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
     * 所有者。
     *
     * @return 用户 id；官方题库为 null
     */
    public Long getOwnerId() {
        return ownerId;
    }

    /**
     * 名称。
     *
     * @return 题库名
     */
    public String getName() {
        return name;
    }

    /**
     * 说明。
     *
     * @return 说明
     */
    public String getDescription() {
        return description;
    }

    /**
     * 可见性。
     *
     * @return PUBLIC/PRIVATE
     */
    public BankVisibility getVisibility() {
        return visibility;
    }

    /**
     * 生命周期状态。
     *
     * @return 状态
     */
    public ContentStatus getStatus() {
        return status;
    }

    /**
     * 银行级修订号。
     *
     * @return 当前版本
     */
    public long getVersion() {
        return version;
    }

    /**
     * 发布时间。
     *
     * @return UTC 时间
     */
    public Instant getPublishedAt() {
        return publishedAt;
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

    /**
     * 软删时间。
     *
     * @return UTC 时间
     */
    public Instant getDeletedAt() {
        return deletedAt;
    }
}

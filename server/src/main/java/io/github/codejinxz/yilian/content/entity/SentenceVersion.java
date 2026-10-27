package io.github.codejinxz.yilian.content.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 不可变内容快照，对应 {@code sentence_versions} 表。
 *
 * <p>chunks/blank/tags 以 JSON 保存；改题必须新增 {@code version_no}，禁止 UPDATE 历史行。
 */
@Entity
@Table(name = "sentence_versions")
public class SentenceVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sentence_id", nullable = false, length = 26)
    private String sentenceId;

    @Column(name = "bank_id", nullable = false)
    private Long bankId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(nullable = false, length = 255)
    private String zh;

    @Column(nullable = false, length = 512)
    private String ref;

    @Column(nullable = false, length = 1000)
    private String note;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String chunks;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String blank;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String tags;

    @Column(length = 32)
    private String level;

    @Column(length = 128)
    private String source;

    @Column(length = 64)
    private String license;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "import_batch_id", length = 36)
    private String importBatchId;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SentenceVersion() {
        // JPA
    }

    /**
     * 创建内容快照。
     *
     * @param sentenceId 题目身份
     * @param bankId 所属题库
     * @param versionNo 单题版本号
     * @param zh 中文原句
     * @param ref 参考译文
     * @param note 解析
     * @param chunks 语块 JSON
     * @param blank 挖空下标 JSON
     * @param tags 考点标签 JSON
     * @param level 等级
     * @param source 来源
     * @param license 许可
     * @param contentHash 内容哈希
     * @param importBatchId 来源批次
     * @param createdBy 创建者
     * @param now 创建时间
     * @return 未持久化实体
     */
    public static SentenceVersion create(
            String sentenceId,
            long bankId,
            int versionNo,
            String zh,
            String ref,
            String note,
            String chunks,
            String blank,
            String tags,
            String level,
            String source,
            String license,
            String contentHash,
            String importBatchId,
            Long createdBy,
            Instant now) {
        SentenceVersion version = new SentenceVersion();
        version.sentenceId = sentenceId;
        version.bankId = bankId;
        version.versionNo = versionNo;
        version.zh = zh;
        version.ref = ref;
        version.note = note;
        version.chunks = chunks;
        version.blank = blank;
        version.tags = tags;
        version.level = level;
        version.source = source;
        version.license = license;
        version.contentHash = contentHash;
        version.schemaVersion = 1;
        version.importBatchId = importBatchId;
        version.createdBy = createdBy;
        version.createdAt = now;
        return version;
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
     * 题目身份。
     *
     * @return ULID
     */
    public String getSentenceId() {
        return sentenceId;
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
     * 单题版本号。
     *
     * @return 从 1 递增
     */
    public int getVersionNo() {
        return versionNo;
    }

    /**
     * 中文原句。
     *
     * @return zh
     */
    public String getZh() {
        return zh;
    }

    /**
     * 参考译文。
     *
     * @return ref
     */
    public String getRef() {
        return ref;
    }

    /**
     * 解析。
     *
     * @return note
     */
    public String getNote() {
        return note;
    }

    /**
     * 语块 JSON。
     *
     * @return JSON 字符串
     */
    public String getChunks() {
        return chunks;
    }

    /**
     * 挖空下标 JSON。
     *
     * @return JSON 字符串
     */
    public String getBlank() {
        return blank;
    }

    /**
     * 考点标签 JSON。
     *
     * @return JSON 字符串
     */
    public String getTags() {
        return tags;
    }

    /**
     * 等级。
     *
     * @return 等级
     */
    public String getLevel() {
        return level;
    }

    /**
     * 内容哈希。
     *
     * @return SHA-256
     */
    public String getContentHash() {
        return contentHash;
    }
}

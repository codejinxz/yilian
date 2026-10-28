package io.github.codejinxz.yilian.upload.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;

/**
 * 导入批次行级结果，对应 {@code import_batch_rows} 表（DATA-MODEL §5.7，V6 迁移）。
 *
 * <p>preview 阶段每行一条：原始行内容（{@code raw}）与逐行错误码（{@code error_codes}）落库， 重启不丢、详情页可分页检索；commit 阶段把 VALID
 * 行置 IMPORTED 并回写题目关联。行内容只服务 导入排错，不写日志、不进缓存。
 */
@Entity
@Table(name = "import_batch_rows")
public class ImportBatchRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false, length = 36)
    private String batchId;

    /** 数据行号，从 1 开始；与 batch_id 一起构成唯一键 uk_import_row。 */
    @Column(name = "row_no", nullable = false)
    private int rowNo;

    @Column(name = "external_key", length = 64)
    private String externalKey;

    /** 原始行内容 JSON 文本；只存原文供排错，不做任何解释执行。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw", nullable = false)
    private String raw;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ImportBatchRowStatus status;

    /** 逐行错误码 JSON 数组文本（§6.7 结构）；校验通过时为 null。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "error_codes")
    private String errorCodes;

    @Column(name = "sentence_id", length = 26)
    private String sentenceId;

    @Column(name = "sentence_version_id")
    private Long sentenceVersionId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ImportBatchRow() {
        // 供 JPA 反射实例化，业务代码请用静态工厂
    }

    private ImportBatchRow(
            String batchId,
            int rowNo,
            String externalKey,
            String raw,
            ImportBatchRowStatus status,
            String errorCodes,
            Instant now) {
        this.batchId = batchId;
        this.rowNo = rowNo;
        this.externalKey = externalKey;
        this.raw = raw;
        this.status = status;
        this.errorCodes = errorCodes;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * 创建一条预览行结果。
     *
     * @param batchId 所属批次 id
     * @param rowNo 数据行号，从 1 开始
     * @param externalKey 源数据自带的 id，允许为 null
     * @param raw 原始行 JSON 文本
     * @param status 行状态，VALID / INVALID
     * @param errorCodes 逐行错误码 JSON 数组文本；VALID 行传 null
     * @param now 创建时间，由调用方统一取时钟便于测试
     * @return 未持久化的行结果
     */
    public static ImportBatchRow previewed(
            String batchId,
            int rowNo,
            String externalKey,
            String raw,
            ImportBatchRowStatus status,
            String errorCodes,
            Instant now) {
        Objects.requireNonNull(batchId, "batchId");
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(now, "now");
        if (rowNo < 1) {
            throw new IllegalArgumentException("行号从 1 开始: " + rowNo);
        }
        if (status == ImportBatchRowStatus.INVALID && errorCodes == null) {
            throw new IllegalArgumentException("INVALID 行必须带错误码");
        }
        return new ImportBatchRow(batchId, rowNo, externalKey, raw, status, errorCodes, now);
    }

    /**
     * commit 成功后回写题目关联；行内容与错误码不变。
     *
     * @param sentenceId 入库生成的题目身份 ULID
     * @param sentenceVersionId 入库生成的内容快照 id
     * @param now 回写时间
     * @throws IllegalStateException 行不处于 {@link ImportBatchRowStatus#VALID} 时抛出
     */
    public void markImported(String sentenceId, Long sentenceVersionId, Instant now) {
        if (status != ImportBatchRowStatus.VALID) {
            throw new IllegalStateException(
                    "只有 VALID 行可以标记入库: batch=" + batchId + " row=" + rowNo + " / " + status);
        }
        Objects.requireNonNull(sentenceId, "sentenceId");
        Objects.requireNonNull(sentenceVersionId, "sentenceVersionId");
        this.sentenceId = sentenceId;
        this.sentenceVersionId = sentenceVersionId;
        this.status = ImportBatchRowStatus.IMPORTED;
        this.updatedAt = now;
    }

    /**
     * 主键。
     *
     * @return 自增 id；未持久化时为 null
     */
    public Long getId() {
        return id;
    }

    /**
     * 所属批次 id。
     *
     * @return 批次 UUID
     */
    public String getBatchId() {
        return batchId;
    }

    /**
     * 数据行号。
     *
     * @return 从 1 开始
     */
    public int getRowNo() {
        return rowNo;
    }

    /**
     * 源数据自带 id。
     *
     * @return 允许为 null
     */
    public String getExternalKey() {
        return externalKey;
    }

    /**
     * 原始行内容。
     *
     * @return JSON 文本
     */
    public String getRaw() {
        return raw;
    }

    /**
     * 行状态。
     *
     * @return VALID / INVALID / IMPORTED / SKIPPED
     */
    public ImportBatchRowStatus getStatus() {
        return status;
    }

    /**
     * 逐行错误码。
     *
     * @return JSON 数组文本；校验通过时为 null
     */
    public String getErrorCodes() {
        return errorCodes;
    }

    /**
     * 关联的题目身份。
     *
     * @return ULID；未入库时为 null
     */
    public String getSentenceId() {
        return sentenceId;
    }

    /**
     * 关联的内容快照 id。
     *
     * @return 未入库时为 null
     */
    public Long getSentenceVersionId() {
        return sentenceVersionId;
    }

    /**
     * 创建时间。
     *
     * @return UTC 时间戳
     */
    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * 最后更新时间。
     *
     * @return UTC 时间戳
     */
    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

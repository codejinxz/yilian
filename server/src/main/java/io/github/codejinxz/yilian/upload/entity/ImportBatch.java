package io.github.codejinxz.yilian.upload.entity;

import io.github.codejinxz.yilian.common.util.StringUtils;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;

/**
 * 导入批次实体，对应 {@code import_batches} 表（DATA-MODEL §5.6，V6 迁移）。
 *
 * <p>preview→commit 契约下的批次聚合根：preview 同步逐行校验后落 {@code PREVIEWED}， 行级结果在 {@link
 * ImportBatchRow}；commit 在单事务内把 VALID 行写为题目。批次本身不保存文件内容， 原文件留在对象存储（{@code storage_key}）供审计下载。
 *
 * <p>状态迁移集中在本类，避免调用方直接改 {@code status} 绕过校验；非法迁移一律抛 {@link
 * IllegalStateException}，由调用方决定如何翻译成业务码。时间统一由调用方传入， 便于测试固定时钟。
 */
@Entity
@Table(name = "import_batches")
public class ImportBatch {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "bank_id", nullable = false)
    private Long bankId;

    /** 文件格式，落库值固定为 {@code JSON} / {@code CSV}（chk_import_format）。 */
    @Column(name = "format", nullable = false, length = 8)
    private String format;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(name = "content_hash", nullable = false, length = 64)
    private String contentHash;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ImportBatchStatus status;

    @Column(name = "total_rows", nullable = false)
    private long totalRows;

    @Column(name = "valid_rows", nullable = false)
    private long validRows;

    @Column(name = "invalid_rows", nullable = false)
    private long invalidRows;

    @Column(name = "imported_rows", nullable = false)
    private long importedRows;

    /** 批次级错误摘要（§6.6 结构的 JSON 文本）；仅服务端可控摘要，不落原文。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "error_summary")
    private String errorSummary;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_by_name", nullable = false, length = 64)
    private String createdByName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "previewed_at")
    private Instant previewedAt;

    @Column(name = "committed_at")
    private Instant committedAt;

    @Column(name = "rolled_back_at")
    private Instant rolledBackAt;

    @Column(name = "rolled_back_by")
    private Long rolledBackBy;

    @Column(name = "rollback_reason", length = 500)
    private String rollbackReason;

    protected ImportBatch() {
        // 供 JPA 反射实例化，业务代码请用静态工厂
    }

    private ImportBatch(
            String id,
            Long bankId,
            String format,
            String originalFilename,
            String storageKey,
            String contentHash,
            long sizeBytes,
            Long createdBy,
            String createdByName,
            Instant now) {
        this.id = id;
        this.bankId = bankId;
        this.format = format;
        this.originalFilename = originalFilename;
        this.storageKey = storageKey;
        this.contentHash = contentHash;
        this.sizeBytes = sizeBytes;
        this.status = ImportBatchStatus.PREVIEWING;
        this.createdBy = createdBy;
        this.createdByName = createdByName;
        this.createdAt = now;
    }

    /**
     * 创建刚接收文件、进入同步校验的批次。
     *
     * @param id 批次主键，UUID 字符串，由调用方生成
     * @param bankId 目标题库 id
     * @param format 文件格式，仅允许 {@code JSON} / {@code CSV}
     * @param originalFilename 已去掉客户端路径的原始文件名
     * @param storageKey 对象存储键
     * @param contentHash 文件 SHA-256 十六进制摘要
     * @param sizeBytes 文件字节数，不能为负
     * @param createdBy 发起导入的管理员 id
     * @param createdByName 管理员用户名快照
     * @param now 创建时间，由调用方统一取时钟便于测试
     * @return 未持久化的批次，状态为 {@link ImportBatchStatus#PREVIEWING}
     */
    public static ImportBatch previewing(
            String id,
            Long bankId,
            String format,
            String originalFilename,
            String storageKey,
            String contentHash,
            long sizeBytes,
            Long createdBy,
            String createdByName,
            Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(bankId, "bankId");
        Objects.requireNonNull(storageKey, "storageKey");
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(now, "now");
        if (!"JSON".equals(format) && !"CSV".equals(format)) {
            throw new IllegalArgumentException("导入格式只允许 JSON 或 CSV: " + format);
        }
        if (StringUtils.isBlank(originalFilename)) {
            throw new IllegalArgumentException("原始文件名不能为空");
        }
        if (StringUtils.isBlank(createdByName)) {
            throw new IllegalArgumentException("创建人用户名不能为空");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("文件大小不能为负数");
        }
        return new ImportBatch(
                id,
                bankId,
                format,
                originalFilename,
                storageKey,
                contentHash,
                sizeBytes,
                createdBy,
                createdByName,
                now);
    }

    /**
     * 预览完成：写入行计数与批次级错误摘要，进入待提交状态。
     *
     * @param totalRows 解析出的数据行数
     * @param validRows 校验通过的行数
     * @param invalidRows 校验失败的行数
     * @param errorSummary 批次级错误摘要 JSON 文本，无错误时为 null
     * @param now 预览完成时间
     * @throws IllegalStateException 批次不处于 {@link ImportBatchStatus#PREVIEWING} 时抛出
     */
    public void markPreviewed(
            long totalRows, long validRows, long invalidRows, String errorSummary, Instant now) {
        requireStatus(ImportBatchStatus.PREVIEWING, "只有预览中的批次可以标记预览完成");
        if (totalRows < 0
                || validRows < 0
                || invalidRows < 0
                || validRows + invalidRows != totalRows) {
            throw new IllegalArgumentException(
                    "行计数不一致: total="
                            + totalRows
                            + " valid="
                            + validRows
                            + " invalid="
                            + invalidRows);
        }
        this.totalRows = totalRows;
        this.validRows = validRows;
        this.invalidRows = invalidRows;
        this.errorSummary = errorSummary;
        this.status = ImportBatchStatus.PREVIEWED;
        this.previewedAt = now;
    }

    /**
     * 进入提交事务；由 commit 入口在写题目之前调用，作为并发重复提交的乐观兜底。
     *
     * @param now 提交开始时间
     * @throws IllegalStateException 批次不处于 {@link ImportBatchStatus#PREVIEWED} 时抛出
     */
    public void markCommitting(Instant now) {
        requireStatus(ImportBatchStatus.PREVIEWED, "只有待提交的批次可以进入提交事务");
        this.status = ImportBatchStatus.COMMITTING;
    }

    /**
     * 提交完成：记录入库行数，进入 {@link ImportBatchStatus#COMMITTED}。
     *
     * @param importedRows 实际入库的题目数
     * @param now 提交完成时间
     * @throws IllegalStateException 批次不处于 {@link ImportBatchStatus#COMMITTING} 时抛出
     */
    public void markCommitted(long importedRows, Instant now) {
        requireStatus(ImportBatchStatus.COMMITTING, "只有提交中的批次可以标记提交完成");
        if (importedRows < 0) {
            throw new IllegalArgumentException("入库行数不能为负数: " + importedRows);
        }
        this.importedRows = importedRows;
        this.status = ImportBatchStatus.COMMITTED;
        this.committedAt = now;
    }

    /**
     * 回滚已提交批次：题目置 RECYCLED 的动作由 Service 完成，本方法只推进批次状态。
     *
     * @param rolledBackBy 执行回滚的管理员 id
     * @param reason 回滚原因，必填（审计要求），最长 500 字符
     * @param now 回滚完成时间
     * @throws IllegalStateException 批次不处于 {@link ImportBatchStatus#COMMITTED} 时抛出
     * @throws IllegalArgumentException 原因为空白时抛出
     */
    public void markRolledBack(Long rolledBackBy, String reason, Instant now) {
        requireStatus(ImportBatchStatus.COMMITTED, "只有已提交的批次可以回滚");
        Objects.requireNonNull(rolledBackBy, "rolledBackBy");
        if (StringUtils.isBlank(reason)) {
            throw new IllegalArgumentException("回滚原因必填");
        }
        this.rolledBackBy = rolledBackBy;
        this.rollbackReason = StringUtils.truncate(reason, 500);
        this.status = ImportBatchStatus.ROLLED_BACK;
        this.rolledBackAt = now;
    }

    /**
     * 标记整批失败。预览/提交任一步的不可恢复异常都落这里；FAILED 为终态，重新导入需新批次。
     *
     * @param errorSummary 失败摘要 JSON 文本，超过合理长度由调用方截断
     * @param now 失败时间
     * @throws IllegalStateException 批次已处于终态或已提交时抛出
     */
    public void markFailed(String errorSummary, Instant now) {
        if (status.isTerminal() || status == ImportBatchStatus.COMMITTED) {
            throw new IllegalStateException("终态或已提交的批次不能标记失败: " + id + " / " + status);
        }
        this.errorSummary = errorSummary;
        this.status = ImportBatchStatus.FAILED;
    }

    /**
     * 清扫超期未提交的批次（DATA-MODEL §5.6：超过 24 小时未提交即 EXPIRED）； PREVIEWING 与 PREVIEWED
     * 都属于未提交，当前只登记状态，清扫任务尚未实现。
     *
     * @param now 清扫时间
     * @throws IllegalStateException 批次已进入提交事务或终态时抛出
     */
    public void markExpired(Instant now) {
        if (status != ImportBatchStatus.PREVIEWING && status != ImportBatchStatus.PREVIEWED) {
            throw new IllegalStateException("只有未提交的预览批次可以超期清理: " + id + " / " + status);
        }
        this.status = ImportBatchStatus.EXPIRED;
    }

    /**
     * 是否已提交且未回滚；重复 commit 的幂等判断依据。
     *
     * @return 状态为 {@link ImportBatchStatus#COMMITTED} 时返回 true
     */
    public boolean isCommitted() {
        return status == ImportBatchStatus.COMMITTED;
    }

    /**
     * 主键。
     *
     * @return 36 位 UUID 字符串
     */
    public String getId() {
        return id;
    }

    /**
     * 目标题库 id。
     *
     * @return 题库 id
     */
    public Long getBankId() {
        return bankId;
    }

    /**
     * 文件格式。
     *
     * @return {@code JSON} 或 {@code CSV}
     */
    public String getFormat() {
        return format;
    }

    /**
     * 原始文件名（已去掉客户端路径）。
     *
     * @return 仅用于展示的文件名
     */
    public String getOriginalFilename() {
        return originalFilename;
    }

    /**
     * 对象存储键。
     *
     * @return 相对路径键，如 {@code imports/2026/10/<uuid>.json}
     */
    public String getStorageKey() {
        return storageKey;
    }

    /**
     * 文件 SHA-256 摘要。
     *
     * @return 十六进制摘要
     */
    public String getContentHash() {
        return contentHash;
    }

    /**
     * 文件字节数。
     *
     * @return 上传时的实际大小
     */
    public long getSizeBytes() {
        return sizeBytes;
    }

    /**
     * 当前状态。
     *
     * @return 批次状态
     */
    public ImportBatchStatus getStatus() {
        return status;
    }

    /**
     * 解析出的数据行数。
     *
     * @return 预览后有效
     */
    public long getTotalRows() {
        return totalRows;
    }

    /**
     * 校验通过的行数。
     *
     * @return 预览后有效
     */
    public long getValidRows() {
        return validRows;
    }

    /**
     * 校验失败的行数。
     *
     * @return 预览后有效；行级失败不改变批次状态
     */
    public long getInvalidRows() {
        return invalidRows;
    }

    /**
     * 已入库的题目数。
     *
     * @return 提交后有效
     */
    public long getImportedRows() {
        return importedRows;
    }

    /**
     * 批次级错误摘要。
     *
     * @return JSON 文本；无错误或未预览时为 null
     */
    public String getErrorSummary() {
        return errorSummary;
    }

    /**
     * 发起导入的管理员 id。
     *
     * @return 管理员 id
     */
    public Long getCreatedBy() {
        return createdBy;
    }

    /**
     * 管理员用户名快照。
     *
     * @return 创建时的用户名
     */
    public String getCreatedByName() {
        return createdByName;
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
     * 预览完成时间。
     *
     * @return UTC 时间戳；未预览完成时为 null
     */
    public Instant getPreviewedAt() {
        return previewedAt;
    }

    /**
     * 提交完成时间。
     *
     * @return UTC 时间戳；未提交时为 null
     */
    public Instant getCommittedAt() {
        return committedAt;
    }

    /**
     * 回滚完成时间。
     *
     * @return UTC 时间戳；未回滚时为 null
     */
    public Instant getRolledBackAt() {
        return rolledBackAt;
    }

    /**
     * 执行回滚的管理员 id。
     *
     * @return 管理员 id；未回滚时为 null
     */
    public Long getRolledBackBy() {
        return rolledBackBy;
    }

    /**
     * 回滚原因。
     *
     * @return 最长 500 字符；未回滚时为 null
     */
    public String getRollbackReason() {
        return rollbackReason;
    }

    /**
     * 断言当前状态匹配预期，否则抛出带批次上下文的异常。
     *
     * @param expected 期望状态
     * @param message 异常文案
     */
    private void requireStatus(ImportBatchStatus expected, String message) {
        if (status != expected) {
            throw new IllegalStateException(message + ": " + id + " / " + status);
        }
    }
}

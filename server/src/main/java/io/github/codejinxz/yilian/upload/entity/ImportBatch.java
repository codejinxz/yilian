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
 * 导入批次实体，对应 {@code import_batch} 表。
 *
 * <p>批次只保存文件元数据与进度，不保存行内容，也不写任何题库业务表。{@code processedRows} 与 {@code resumeOffset} 始终一起推进：只有每 100
 * 行（或处理结束）持久化进度时，才把内存计数写回实体，因此发生不可恢复错误时实体仍停留在最近一次检查点，resume 会从该检查点重新读取。 <strong>这是 at-least-once
 * 语义</strong>：检查点之后、失败之前的行会被再次读取；行处理器必须幂等，写业务表时必须用 {@code (batch_id, line_no)} 唯一约束或 upsert 兜底，不能把
 * resume 当成 exactly-once。
 *
 * <p>状态迁移集中在本类，避免调用方直接改 {@code status} 绕过校验；{@link #COMPLETED} 为终态， 任何重新处理都必须先经过 Service 的幂等判断。
 */
@Entity
@Table(name = "import_batch")
public class ImportBatch {

    /** 错误摘要最多保留的字符数，与 {@code error_message VARCHAR(1000)} 对齐。 */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ImportBatchStatus status;

    @Column(name = "total_rows", nullable = false)
    private long totalRows;

    @Column(name = "processed_rows", nullable = false)
    private long processedRows;

    @Column(name = "success_rows", nullable = false)
    private long successRows;

    @Column(name = "failed_rows", nullable = false)
    private long failedRows;

    @Column(name = "resume_offset", nullable = false)
    private long resumeOffset;

    @Column(name = "error_message", length = MAX_ERROR_MESSAGE_LENGTH)
    private String errorMessage;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ImportBatch() {
        // 供 JPA 反射实例化，业务代码请用静态工厂
    }

    private ImportBatch(
            String id,
            String originalFilename,
            String storageKey,
            long sizeBytes,
            String contentType,
            String createdBy,
            Instant now) {
        this.id = id;
        this.originalFilename = originalFilename;
        this.storageKey = storageKey;
        this.sizeBytes = sizeBytes;
        this.contentType = contentType;
        this.status = ImportBatchStatus.UPLOADED;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * 创建刚完成文件上传的批次。
     *
     * @param id 批次主键，UUID 字符串，由调用方生成
     * @param originalFilename 已去掉客户端路径的原始文件名
     * @param storageKey 对象存储键
     * @param sizeBytes 文件字节数，不能为负
     * @param contentType 归一化后的 MIME 类型，允许为 null
     * @param createdBy 上传管理员的用户名，不能为空白
     * @param now 创建时间，由调用方统一取时钟便于测试
     * @return 未持久化的批次，状态为 {@link ImportBatchStatus#UPLOADED}
     */
    public static ImportBatch uploaded(
            String id,
            String originalFilename,
            String storageKey,
            long sizeBytes,
            String contentType,
            String createdBy,
            Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(storageKey, "storageKey");
        Objects.requireNonNull(now, "now");
        if (StringUtils.isBlank(originalFilename)) {
            throw new IllegalArgumentException("原始文件名不能为空");
        }
        if (StringUtils.isBlank(createdBy)) {
            throw new IllegalArgumentException("创建人不能为空");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("文件大小不能为负数");
        }
        return new ImportBatch(
                id, originalFilename, storageKey, sizeBytes, contentType, createdBy, now);
    }

    /**
     * 进入处理中状态；已完成的批次不允许重跑。
     *
     * @throws IllegalStateException 批次已 {@link ImportBatchStatus#COMPLETED} 时抛出
     */
    public void markProcessing() {
        if (status == ImportBatchStatus.COMPLETED) {
            throw new IllegalStateException("已完成的导入批次不能重新处理: " + id);
        }
        this.status = ImportBatchStatus.PROCESSING;
        this.errorMessage = null;
        this.updatedAt = Instant.now();
    }

    /**
     * 写入一次进度检查点。计数与 {@code resumeOffset} 同步推进，保证 resume 时跳过的行数与持久化进度一致。
     *
     * @param totalRows 已读到的数据行数
     * @param processedRows 已处理的数据行数
     * @param successRows 校验成功的行数
     * @param failedRows 校验失败的行数
     * @throws IllegalStateException 批次不处于 {@link ImportBatchStatus#PROCESSING} 时抛出
     */
    public void applyProgress(
            long totalRows, long processedRows, long successRows, long failedRows) {
        if (status != ImportBatchStatus.PROCESSING) {
            throw new IllegalStateException("只有处理中的批次可以写进度: " + id);
        }
        this.totalRows = totalRows;
        this.processedRows = processedRows;
        this.successRows = successRows;
        this.failedRows = failedRows;
        this.resumeOffset = processedRows;
        this.updatedAt = Instant.now();
    }

    /**
     * 标记整个文件处理完成；终态，后续 resume 幂等返回。
     *
     * @throws IllegalStateException 批次不处于 {@link ImportBatchStatus#PROCESSING} 时抛出
     */
    public void complete() {
        if (status != ImportBatchStatus.PROCESSING) {
            throw new IllegalStateException("只有处理中的批次可以完成: " + id);
        }
        this.totalRows = this.processedRows;
        this.resumeOffset = this.processedRows;
        this.status = ImportBatchStatus.COMPLETED;
        this.errorMessage = null;
        this.updatedAt = Instant.now();
    }

    /**
     * 标记整批失败。计数保持最近一次检查点，{@code resumeOffset} 不动，因此可以从失败前的进度续跑。
     *
     * @param message 失败原因，只写服务端可控的摘要，不写文件内容；超过 1000 字符会被截断
     * @throws IllegalStateException 批次已 {@link ImportBatchStatus#COMPLETED} 时抛出
     */
    public void fail(String message) {
        if (status == ImportBatchStatus.COMPLETED) {
            throw new IllegalStateException("已完成的导入批次不能标记失败: " + id);
        }
        this.status = ImportBatchStatus.FAILED;
        this.errorMessage = StringUtils.truncate(message, MAX_ERROR_MESSAGE_LENGTH);
        this.updatedAt = Instant.now();
    }

    /**
     * 暂停处理中的批次，保留进度供后续 resume；当前只由启动恢复钩子使用。
     *
     * @param message 暂停原因，超过 1000 字符会被截断
     * @throws IllegalStateException 批次不处于 {@link ImportBatchStatus#PROCESSING} 时抛出
     */
    public void pause(String message) {
        if (status != ImportBatchStatus.PROCESSING) {
            throw new IllegalStateException("只有处理中的批次可以暂停: " + id);
        }
        this.status = ImportBatchStatus.PAUSED;
        this.errorMessage = StringUtils.truncate(message, MAX_ERROR_MESSAGE_LENGTH);
        this.updatedAt = Instant.now();
    }

    /**
     * 是否已处于终态。
     *
     * @return 状态为 {@link ImportBatchStatus#COMPLETED} 时返回 true
     */
    public boolean isCompleted() {
        return status == ImportBatchStatus.COMPLETED;
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
     * 原始文件名（已去掉客户端路径）。
     *
     * @return 用于展示和判断文件格式的文件名
     */
    public String getOriginalFilename() {
        return originalFilename;
    }

    /**
     * 对象存储键。
     *
     * @return 相对路径键，如 {@code imports/2026/10/<uuid>.csv}
     */
    public String getStorageKey() {
        return storageKey;
    }

    /**
     * 文件字节数。
     *
     * @return 上传时声明的长度
     */
    public long getSizeBytes() {
        return sizeBytes;
    }

    /**
     * 归一化后的 MIME 类型。
     *
     * @return 如 {@code text/csv}；未知时为 null
     */
    public String getContentType() {
        return contentType;
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
     * 已读到的数据行数。
     *
     * @return 处理完成后与 {@link #getProcessedRows()} 相等
     */
    public long getTotalRows() {
        return totalRows;
    }

    /**
     * 已处理的数据行数（含失败行）。
     *
     * @return 已处理行数
     */
    public long getProcessedRows() {
        return processedRows;
    }

    /**
     * 校验成功的行数。
     *
     * @return 成功行数
     */
    public long getSuccessRows() {
        return successRows;
    }

    /**
     * 校验失败的行数。
     *
     * @return 失败行数；行级失败不改变批次状态
     */
    public long getFailedRows() {
        return failedRows;
    }

    /**
     * 已持久化的处理进度。
     *
     * @return resume 时从文件开头跳过的行数
     */
    public long getResumeOffset() {
        return resumeOffset;
    }

    /**
     * 整批失败或暂停的原因。
     *
     * @return 最近一次失败/暂停摘要；正常完成或处理中为 null
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * 上传管理员用户名。
     *
     * @return 创建人，不建外键
     */
    public String getCreatedBy() {
        return createdBy;
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

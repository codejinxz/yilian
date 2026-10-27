package io.github.codejinxz.yilian.upload.dto;

import io.github.codejinxz.yilian.upload.entity.ImportBatch;
import io.github.codejinxz.yilian.upload.entity.ImportBatchStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 批次状态与进度视图，用于轮询接口与 resume 接口。
 *
 * <p>{@code totalRows} 在处理中是「已读到的行数」，处理完成后与 {@code processedRows} 相等； 前端展示进度时应优先用 {@code
 * processedRows}，不要假设一开始就知道文件总行数。
 *
 * @param batchId 批次主键
 * @param originalFilename 原始文件名
 * @param sizeBytes 文件字节数
 * @param contentType 归一化后的 MIME 类型
 * @param status 批次状态
 * @param totalRows 已读到的数据行数
 * @param processedRows 已处理行数（含失败行）
 * @param successRows 校验成功行数
 * @param failedRows 校验失败行数
 * @param resumeOffset 已持久化的进度，resume 时跳过该行数
 * @param errorMessage 整批失败/暂停原因；正常时为 null
 * @param createdAt 创建时间
 * @param updatedAt 最后更新时间
 */
@Schema(description = "导入批次状态与进度；processedRows 是已持久化的处理行数。")
public record ImportBatchStatusResponse(
        String batchId,
        String originalFilename,
        long sizeBytes,
        String contentType,
        ImportBatchStatus status,
        long totalRows,
        long processedRows,
        long successRows,
        long failedRows,
        long resumeOffset,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 从实体生成状态视图。
     *
     * @param batch 批次实体
     * @return 对外状态视图
     */
    public static ImportBatchStatusResponse from(ImportBatch batch) {
        return new ImportBatchStatusResponse(
                batch.getId(),
                batch.getOriginalFilename(),
                batch.getSizeBytes(),
                batch.getContentType(),
                batch.getStatus(),
                batch.getTotalRows(),
                batch.getProcessedRows(),
                batch.getSuccessRows(),
                batch.getFailedRows(),
                batch.getResumeOffset(),
                batch.getErrorMessage(),
                batch.getCreatedAt(),
                batch.getUpdatedAt());
    }
}

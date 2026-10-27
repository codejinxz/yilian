package io.github.codejinxz.yilian.upload.dto;

import io.github.codejinxz.yilian.upload.entity.ImportBatch;
import io.github.codejinxz.yilian.upload.entity.ImportBatchStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 创建批次后的 202 响应体。
 *
 * <p>只返回最小信息：前端拿到 {@code batchId} 后轮询 {@code GET /api/admin/uploads/{id}}。 上传接口不等待文件处理完成，避免 5MB
 * 文件的逐行校验阻塞请求线程。
 *
 * @param batchId 批次主键，用于轮询与续跑
 * @param status 创建瞬间的状态，正常为 {@link ImportBatchStatus#UPLOADED}
 */
@Schema(description = "导入批次已受理：用 batchId 轮询进度。")
public record ImportBatchAccepted(
        @Schema(description = "批次主键", example = "550e8400-e29b-41d4-a716-446655440000")
                String batchId,
        @Schema(description = "创建瞬间的批次状态", example = "UPLOADED") ImportBatchStatus status) {

    /**
     * 从实体生成受理视图。
     *
     * @param batch 已持久化的批次
     * @return 只含 id 与状态的响应体
     */
    public static ImportBatchAccepted from(ImportBatch batch) {
        return new ImportBatchAccepted(batch.getId(), batch.getStatus());
    }
}

package io.github.codejinxz.yilian.upload.dto;

import io.github.codejinxz.yilian.upload.entity.ImportBatch;
import io.github.codejinxz.yilian.upload.entity.ImportBatchStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 失败行摘要。
 *
 * <p>当前只返回计数与最多 {@code 20} 条内存中的行级样例；应用重启后样例会丢失， 但 {@code failedRows} 计数仍可从数据库恢复。后续如果需要持久化逐行错误， 再增加
 * {@code import_row_error} 表，不要把整行内容写进 {@code error_message}。
 *
 * @param batchId 批次主键
 * @param status 批次状态
 * @param totalRows 已读到的数据行数
 * @param processedRows 已处理行数
 * @param successRows 成功行数
 * @param failedRows 失败行数
 * @param errorMessage 整批失败/暂停原因
 * @param truncated 失败行数是否多于返回的样例数
 * @param errors 行级失败样例，按发生顺序排列
 */
@Schema(description = "导入失败行摘要：计数来自数据库，行级样例来自进程内最近 20 条。")
public record ImportBatchErrorsResponse(
        String batchId,
        ImportBatchStatus status,
        long totalRows,
        long processedRows,
        long successRows,
        long failedRows,
        String errorMessage,
        boolean truncated,
        List<ImportRowError> errors) {

    /**
     * 组装失败摘要。
     *
     * @param batch 批次实体
     * @param errors 当前进程内保存的失败样例
     * @return 对外摘要
     */
    public static ImportBatchErrorsResponse of(ImportBatch batch, List<ImportRowError> errors) {
        return new ImportBatchErrorsResponse(
                batch.getId(),
                batch.getStatus(),
                batch.getTotalRows(),
                batch.getProcessedRows(),
                batch.getSuccessRows(),
                batch.getFailedRows(),
                batch.getErrorMessage(),
                batch.getFailedRows() > errors.size(),
                List.copyOf(errors));
    }
}

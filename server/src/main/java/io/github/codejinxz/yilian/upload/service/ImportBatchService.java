package io.github.codejinxz.yilian.upload.service;

import io.github.codejinxz.yilian.upload.dto.ImportBatchAccepted;
import io.github.codejinxz.yilian.upload.dto.ImportBatchErrorsResponse;
import io.github.codejinxz.yilian.upload.dto.ImportBatchStatusResponse;
import io.github.codejinxz.yilian.upload.dto.ImportUploadCommand;

/**
 * 导入批次用例服务。
 *
 * <p>上传只负责校验元数据、流式写对象存储与创建批次，逐行处理在独立线程池中异步执行； 查询、失败摘要与断点续跑都通过批次 id 定位。Controller 只依赖本接口。
 */
public interface ImportBatchService {

    /**
     * 受理一次导入上传。
     *
     * <p>方法返回时文件已完整写入对象存储、批次已持久化，但处理通常尚未开始； 返回 202 语义的受理视图，调用方用 {@code batchId} 轮询。
     *
     * @param command 上传元数据与内容流
     * @return 批次 id 与受理瞬间的状态
     */
    ImportBatchAccepted upload(ImportUploadCommand command);

    /**
     * 查询批次状态与进度。
     *
     * @param batchId 批次 id
     * @return 状态与计数
     */
    ImportBatchStatusResponse status(String batchId);

    /**
     * 从最近持久化的 {@code resumeOffset} 续跑。
     *
     * <p>已 {@code COMPLETED} 的批次幂等返回，不会重新读取文件；正在处理中的批次直接返回当前状态。
     *
     * @param batchId 批次 id
     * @return 续跑受理后的状态视图
     */
    ImportBatchStatusResponse resume(String batchId);

    /**
     * 查询失败行摘要。
     *
     * @param batchId 批次 id
     * @return 失败计数与最近的行级样例
     */
    ImportBatchErrorsResponse errors(String batchId);

    /**
     * 启动恢复：把上次进程遗留的 {@code PROCESSING} 批次转 {@code PAUSED}。
     *
     * <p>导入线程只存在于进程内，进程退出后不会自动继续；转 PAUSED 并写入原因，让管理员显式调用 {@link
     * #resume(String)}，避免重启后无声地重复处理文件。单个批次处理失败只记日志，不阻塞应用启动。
     */
    void recoverInterrupted();
}

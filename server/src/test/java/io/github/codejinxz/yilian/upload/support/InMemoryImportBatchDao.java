package io.github.codejinxz.yilian.upload.support;

import io.github.codejinxz.yilian.upload.dao.ImportBatchDao;
import io.github.codejinxz.yilian.upload.entity.ImportBatch;
import io.github.codejinxz.yilian.upload.entity.ImportBatchStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 测试用内存批次 Dao，同时记录每次 save 的计数快照，便于断言「每 100 行持久化」。 */
public final class InMemoryImportBatchDao implements ImportBatchDao {

    private final Map<String, ImportBatch> batches = new ConcurrentHashMap<>();

    private final List<Snapshot> snapshots = Collections.synchronizedList(new ArrayList<>());

    /**
     * 保存批次并记录快照。
     *
     * @param batch 批次
     * @return 同一实例
     */
    @Override
    public ImportBatch save(ImportBatch batch) {
        batches.put(batch.getId(), batch);
        snapshots.add(Snapshot.from(batch));
        return batch;
    }

    /**
     * 按 id 查询。
     *
     * @param id 批次 id
     * @return 批次
     */
    @Override
    public Optional<ImportBatch> findById(String id) {
        return Optional.ofNullable(batches.get(id));
    }

    /**
     * 按状态查询。
     *
     * @param status 状态
     * @return 匹配批次
     */
    @Override
    public List<ImportBatch> findByStatus(ImportBatchStatus status) {
        return batches.values().stream().filter(batch -> batch.getStatus() == status).toList();
    }

    /**
     * 已保存的进度快照。
     *
     * @return 快照副本
     */
    public List<Snapshot> snapshots() {
        synchronized (snapshots) {
            return List.copyOf(snapshots);
        }
    }

    /**
     * 一次 save 时的关键字段快照。
     *
     * @param batchId 批次 id
     * @param status 状态
     * @param totalRows 已读行数
     * @param processedRows 已处理行数
     * @param successRows 成功行数
     * @param failedRows 失败行数
     * @param resumeOffset 持久化进度
     */
    public record Snapshot(
            String batchId,
            ImportBatchStatus status,
            long totalRows,
            long processedRows,
            long successRows,
            long failedRows,
            long resumeOffset) {

        /**
         * 从实体生成快照。
         *
         * @param batch 批次
         * @return 快照
         */
        static Snapshot from(ImportBatch batch) {
            return new Snapshot(
                    batch.getId(),
                    batch.getStatus(),
                    batch.getTotalRows(),
                    batch.getProcessedRows(),
                    batch.getSuccessRows(),
                    batch.getFailedRows(),
                    batch.getResumeOffset());
        }
    }
}

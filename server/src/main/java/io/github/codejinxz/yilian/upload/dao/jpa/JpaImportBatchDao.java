package io.github.codejinxz.yilian.upload.dao.jpa;

import io.github.codejinxz.yilian.upload.dao.ImportBatchDao;
import io.github.codejinxz.yilian.upload.entity.ImportBatch;
import io.github.codejinxz.yilian.upload.entity.ImportBatchStatus;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 基于 Spring Data JPA 的批次 Dao 实现。
 *
 * <p>preview→commit 全同步，不存在跨请求的后台任务；批次行数少，Repository 自带事务足够，
 * 只有 commit 写题库业务表时才由 Service 在同一事务内聚合多次写。
 */
@Repository
public class JpaImportBatchDao implements ImportBatchDao {

    private final ImportBatchJpaRepository repository;

    /**
     * 只依赖仓储接口，测试可替换为内存实现。
     *
     * @param repository Spring Data 仓储
     */
    public JpaImportBatchDao(ImportBatchJpaRepository repository) {
        this.repository = repository;
    }

    /**
     * 保存批次。
     *
     * @param batch 待保存批次
     * @return 保存后的批次
     */
    @Override
    public ImportBatch save(ImportBatch batch) {
        return repository.save(batch);
    }

    /**
     * 按主键查询。
     *
     * @param id 批次 id
     * @return 批次，不存在时为空
     */
    @Override
    public Optional<ImportBatch> findById(String id) {
        return repository.findById(id);
    }

    /**
     * 按状态查询。
     *
     * @param status 目标状态
     * @return 匹配的批次
     */
    @Override
    public List<ImportBatch> findByStatus(ImportBatchStatus status) {
        return repository.findByStatusOrderByCreatedAtAsc(status);
    }
}

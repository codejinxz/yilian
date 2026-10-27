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
 * <p>每次调用由 Repository 自带的事务提交，Service 不需要包大事务：分片进度按 100 行独立提交， 长导入不会长期占用连接；失败时也只回滚最近一次检查点之后的内存计数。
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

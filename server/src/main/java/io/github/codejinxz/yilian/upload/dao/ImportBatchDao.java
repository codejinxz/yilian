package io.github.codejinxz.yilian.upload.dao;

import io.github.codejinxz.yilian.upload.entity.ImportBatch;
import io.github.codejinxz.yilian.upload.entity.ImportBatchStatus;

import java.util.List;
import java.util.Optional;

/**
 * 导入批次 Dao：业务层只依赖本接口，不直接使用 Spring Data Repository。
 *
 * <p>批次写操作很少，全部走单表 JPA CRUD；后续题库导入需要批量写业务表时，按 AGENTS.md §3.1 在同一个事务内用 MyBatis
 * 写入，并保留可回滚与测试，不要绕过本接口的状态机。
 */
public interface ImportBatchDao {

    /**
     * 保存批次；主键已存在时按 JPA merge 语义更新。
     *
     * @param batch 待保存批次
     * @return 保存后的批次
     */
    ImportBatch save(ImportBatch batch);

    /**
     * 按主键查询。
     *
     * @param id 批次 id
     * @return 批次，不存在时为空
     */
    Optional<ImportBatch> findById(String id);

    /**
     * 按状态查询，启动恢复时由 Service 用它找出遗留的 PROCESSING 批次。
     *
     * @param status 目标状态
     * @return 匹配的批次，按创建时间升序
     */
    List<ImportBatch> findByStatus(ImportBatchStatus status);
}

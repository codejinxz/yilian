package io.github.codejinxz.yilian.upload.dao.jpa;

import io.github.codejinxz.yilian.upload.entity.ImportBatch;
import io.github.codejinxz.yilian.upload.entity.ImportBatchStatus;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA 仓储，仅作为 {@link JpaImportBatchDao} 的实现细节。
 *
 * <p>业务代码不要注入本接口；这样后续把批次表迁到 MyBatis 或换存储时，Service 不需要改动。
 */
public interface ImportBatchJpaRepository extends JpaRepository<ImportBatch, String> {

    /**
     * 按状态查询，创建时间升序保证恢复顺序稳定。
     *
     * @param status 目标状态
     * @return 匹配的批次
     */
    List<ImportBatch> findByStatusOrderByCreatedAtAsc(ImportBatchStatus status);
}

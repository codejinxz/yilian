package io.github.codejinxz.yilian.upload.dao.jpa;

import io.github.codejinxz.yilian.upload.entity.ImportBatchRow;
import io.github.codejinxz.yilian.upload.entity.ImportBatchRowStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 导入行 Spring Data 仓库，仅作为 {@link JpaImportBatchRowDao} 的实现细节。 */
public interface ImportBatchRowJpaRepository extends JpaRepository<ImportBatchRow, Long> {

    /**
     * 按批次取全部行，行号升序。
     *
     * @param batchId 批次 id
     * @return 行集合
     */
    List<ImportBatchRow> findByBatchIdOrderByRowNoAsc(String batchId);

    /**
     * 按批次与状态取行，行号升序。
     *
     * @param batchId 批次 id
     * @param status 行状态
     * @return 行集合
     */
    List<ImportBatchRow> findByBatchIdAndStatusOrderByRowNoAsc(
            String batchId, ImportBatchRowStatus status);

    /**
     * 批次行分页。
     *
     * @param batchId 批次 id
     * @param pageable 分页
     * @return 行分页
     */
    Page<ImportBatchRow> findByBatchId(String batchId, Pageable pageable);

    /**
     * 批次行分页（带状态过滤）。
     *
     * @param batchId 批次 id
     * @param status 行状态
     * @param pageable 分页
     * @return 行分页
     */
    Page<ImportBatchRow> findByBatchIdAndStatus(
            String batchId, ImportBatchRowStatus status, Pageable pageable);
}

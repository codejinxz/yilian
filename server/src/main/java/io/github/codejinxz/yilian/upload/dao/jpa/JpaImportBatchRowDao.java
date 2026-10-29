package io.github.codejinxz.yilian.upload.dao.jpa;

import io.github.codejinxz.yilian.upload.dao.ImportBatchRowDao;
import io.github.codejinxz.yilian.upload.entity.ImportBatchRow;
import io.github.codejinxz.yilian.upload.entity.ImportBatchRowStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/** 导入行 Dao 的 JPA 实现。 */
@Repository
public class JpaImportBatchRowDao implements ImportBatchRowDao {

    private final ImportBatchRowJpaRepository rows;

    /**
     * @param rows Spring Data 仓储
     */
    public JpaImportBatchRowDao(ImportBatchRowJpaRepository rows) {
        this.rows = rows;
    }

    @Override
    public List<ImportBatchRow> saveAll(Collection<ImportBatchRow> rows) {
        return this.rows.saveAll(rows);
    }

    @Override
    public List<ImportBatchRow> findByBatchId(String batchId) {
        return rows.findByBatchIdOrderByRowNoAsc(batchId);
    }

    @Override
    public List<ImportBatchRow> findByBatchIdAndStatus(String batchId, ImportBatchRowStatus status) {
        return rows.findByBatchIdAndStatusOrderByRowNoAsc(batchId, status);
    }

    @Override
    public Page<ImportBatchRow> findPage(
            String batchId, ImportBatchRowStatus status, Pageable pageable) {
        Pageable ordered = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by("rowNo"));
        if (status == null) {
            return rows.findByBatchId(batchId, ordered);
        }
        return rows.findByBatchIdAndStatus(batchId, status, ordered);
    }

    @Override
    public ImportBatchRow save(ImportBatchRow row) {
        return rows.save(row);
    }
}

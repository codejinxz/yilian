package io.github.codejinxz.yilian.upload.dao.mybatis;

import io.github.codejinxz.yilian.upload.dao.ImportBatchQueryDao;
import io.github.codejinxz.yilian.upload.dto.ImportBatchListItem;
import io.github.codejinxz.yilian.upload.dto.ImportBatchRowItem;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * {@link ImportBatchQueryDao} 的 MyBatis 适配器，与 {@code dao.jpa.JpaXxxDao} 模式对称： 契约在 {@code dao} 包，实现在
 * {@code dao.mybatis}，业务层无感知。
 */
@Repository
public class MyBatisImportBatchQueryDao implements ImportBatchQueryDao {

    private final ImportBatchQueryMapper mapper;

    /**
     * @param mapper MyBatis Mapper 代理
     */
    public MyBatisImportBatchQueryDao(ImportBatchQueryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<ImportBatchListItem> findBatches(
            Long bankId, String status, Instant from, Instant to, long offset, int limit) {
        return mapper.findBatches(bankId, status, from, to, offset, limit);
    }

    @Override
    public long countBatches(Long bankId, String status, Instant from, Instant to) {
        return mapper.countBatches(bankId, status, from, to);
    }

    @Override
    public List<ImportBatchRowItem> findRows(
            String batchId, String status, long offset, int limit) {
        return mapper.findRows(batchId, status, offset, limit);
    }

    @Override
    public long countRows(String batchId, String status) {
        return mapper.countRows(batchId, status);
    }
}

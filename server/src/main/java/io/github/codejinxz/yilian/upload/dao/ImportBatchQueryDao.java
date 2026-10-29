package io.github.codejinxz.yilian.upload.dao;

import io.github.codejinxz.yilian.upload.dto.ImportBatchListItem;
import io.github.codejinxz.yilian.upload.dto.ImportBatchRowItem;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * 导入批次/行动态查询 Dao（MyBatis 只读 DTO，AGENTS §3.1「MyBatis 负责复杂查询」）。
 *
 * <p>批次列表支持题库/状态/时间范围动态筛选；行查询支持状态过滤。写入仍走 JPA Dao。
 */
public interface ImportBatchQueryDao {

    /**
     * 动态筛选批次列表，创建时间倒序。
     *
     * @param bankId 题库 id，null 不过滤
     * @param status 批次状态，null 不过滤
     * @param from 创建时间下界（含），null 不过滤
     * @param to 创建时间上界（含），null 不过滤
     * @param offset 偏移
     * @param limit 每页条数
     * @return 批次列表项
     */
    List<ImportBatchListItem> findBatches(
            Long bankId, String status, Instant from, Instant to, long offset, int limit);

    /**
     * 动态筛选批次总数。
     *
     * @param bankId 题库 id，null 不过滤
     * @param status 批次状态，null 不过滤
     * @param from 创建时间下界（含），null 不过滤
     * @param to 创建时间上界（含），null 不过滤
     * @return 总数
     */
    long countBatches(Long bankId, String status, Instant from, Instant to);

    /**
     * 批次行分页，行号升序。
     *
     * @param batchId 批次 id
     * @param status 行状态，null 不过滤
     * @param offset 偏移
     * @param limit 每页条数
     * @return 行列表项
     */
    List<ImportBatchRowItem> findRows(String batchId, String status, long offset, int limit);

    /**
     * 批次行总数。
     *
     * @param batchId 批次 id
     * @param status 行状态，null 不过滤
     * @return 总数
     */
    long countRows(String batchId, String status);
}

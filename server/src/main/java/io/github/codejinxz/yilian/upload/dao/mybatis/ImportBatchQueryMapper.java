package io.github.codejinxz.yilian.upload.dao.mybatis;

import io.github.codejinxz.yilian.upload.dao.ImportBatchQueryDao;
import io.github.codejinxz.yilian.upload.dto.ImportBatchListItem;
import io.github.codejinxz.yilian.upload.dto.ImportBatchRowItem;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/** 导入批次动态查询 Mapper：SQL 在 {@code mapper/ImportBatchQueryMapper.xml}。实现细节，业务层勿直接注入。 */
@Mapper
public interface ImportBatchQueryMapper {

    /**
     * 动态筛选批次列表。
     *
     * @param bankId 题库 id
     * @param status 批次状态
     * @param from 时间下界
     * @param to 时间上界
     * @param offset 偏移
     * @param limit 每页条数
     * @return 批次列表项
     */
    List<ImportBatchListItem> findBatches(
            @Param("bankId") Long bankId,
            @Param("status") String status,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("offset") long offset,
            @Param("limit") int limit);

    /**
     * 动态筛选批次总数。
     *
     * @param bankId 题库 id
     * @param status 批次状态
     * @param from 时间下界
     * @param to 时间上界
     * @return 总数
     */
    long countBatches(
            @Param("bankId") Long bankId,
            @Param("status") String status,
            @Param("from") Instant from,
            @Param("to") Instant to);

    /**
     * 批次行分页。
     *
     * @param batchId 批次 id
     * @param status 行状态
     * @param offset 偏移
     * @param limit 每页条数
     * @return 行列表项
     */
    List<ImportBatchRowItem> findRows(
            @Param("batchId") String batchId,
            @Param("status") String status,
            @Param("offset") long offset,
            @Param("limit") int limit);

    /**
     * 批次行总数。
     *
     * @param batchId 批次 id
     * @param status 行状态
     * @return 总数
     */
    long countRows(@Param("batchId") String batchId, @Param("status") String status);
}

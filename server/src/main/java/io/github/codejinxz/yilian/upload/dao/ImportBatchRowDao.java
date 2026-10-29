package io.github.codejinxz.yilian.upload.dao;

import io.github.codejinxz.yilian.upload.entity.ImportBatchRow;
import io.github.codejinxz.yilian.upload.entity.ImportBatchRowStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;

/**
 * 导入行 Dao：业务层只依赖本接口，不直接使用 Spring Data Repository。
 *
 * <p>行写入只在 preview（批量落库）与 commit（回写关联）时发生；分页查询走本接口的
 * 派生方法，动态筛选的批次列表见 {@link ImportBatchQueryDao}（MyBatis）。
 */
public interface ImportBatchRowDao {

    /**
     * 批量保存行结果。
     *
     * @param rows 行集合
     * @return 保存后的行集合
     */
    List<ImportBatchRow> saveAll(Collection<ImportBatchRow> rows);

    /**
     * 按批次取全部行，行号升序。
     *
     * @param batchId 批次 id
     * @return 行集合
     */
    List<ImportBatchRow> findByBatchId(String batchId);

    /**
     * 按批次与状态取行，行号升序（commit 只取 VALID）。
     *
     * @param batchId 批次 id
     * @param status 行状态
     * @return 行集合
     */
    List<ImportBatchRow> findByBatchIdAndStatus(String batchId, ImportBatchRowStatus status);

    /**
     * 批次行分页（详情页），可选状态过滤。
     *
     * @param batchId 批次 id
     * @param status 行状态，null 不过滤
     * @param pageable 分页
     * @return 行分页
     */
    Page<ImportBatchRow> findPage(String batchId, ImportBatchRowStatus status, Pageable pageable);

    /**
     * 回写行结果（commit 关联题目后）。
     *
     * @param row 行
     * @return 保存后的行
     */
    ImportBatchRow save(ImportBatchRow row);
}

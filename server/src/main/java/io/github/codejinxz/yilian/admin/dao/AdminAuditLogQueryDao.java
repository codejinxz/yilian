package io.github.codejinxz.yilian.admin.dao;

import java.util.List;

/**
 * 管理活动只读查询 Dao（MyBatis 实现）。
 *
 * <p>{@code admin_audit_log} 是 V5 的按月分区表（主键含 created_at），没有 JPA 实体； 概览的最近活动走本 Dao 只读倒序，不提供任何写路径。
 */
public interface AdminAuditLogQueryDao {

    /**
     * 取最近的管理活动，按 created_at 倒序、同刻再按 id 倒序保证稳定。
     *
     * @param limit 最多返回条数，必须为正
     * @return 活动行
     */
    List<AuditActivityRow> findRecent(int limit);
}

package io.github.codejinxz.yilian.admin.dao;

import io.github.codejinxz.yilian.admin.service.vo.AdminAuditLogView;

import java.time.Instant;
import java.util.List;

/**
 * 管理活动只读查询 Dao（MyBatis 实现）。
 *
 * <p>{@code admin_audit_log} 是 V5 的按月分区表（主键含 created_at），没有 JPA 实体； 概览的最近活动与操作日志检索都走本 Dao
 * 只读，不提供任何写路径。 检索查询必须带 created_at 范围（分区键），由服务层保证缺省兜底近 30 天。
 */
public interface AdminAuditLogQueryDao {

    /**
     * 取最近的管理活动，按 created_at 倒序、同刻再按 id 倒序保证稳定。
     *
     * @param limit 最多返回条数，必须为正
     * @return 活动行
     */
    List<AuditActivityRow> findRecent(int limit);

    /**
     * 按条件检索操作日志，按 created_at 倒序、同刻再按 id 倒序保证稳定；分页窗口由调用方换算。
     *
     * <p>时间范围是分区键条件，必须非空（缺省窗口由服务层兜底）；keyword 在服务层已做 LIKE 通配转义， 本 Dao 不再加工，直接传给 SQL 拼串 contains。
     *
     * @param keyword 拼串模糊匹配（action/actor_name/target_type/target_id/reason），null 表示不过滤
     * @param module 模块精确过滤，null 表示不过滤
     * @param action 动作精确过滤，null 表示不过滤
     * @param actorId 操作者精确过滤，null 表示不过滤
     * @param from 窗口起点（含），非空
     * @param to 窗口终点（不含），非空
     * @param offset 偏移量，从 0 起
     * @param limit 每页条数，1–100
     * @return 当前页日志行
     */
    List<AdminAuditLogView> search(
            String keyword,
            String module,
            String action,
            Long actorId,
            Instant from,
            Instant to,
            int offset,
            int limit);

    /**
     * 与 {@link #search} 同条件的总数；分页元数据用。
     *
     * @param keyword 拼串模糊匹配，null 表示不过滤
     * @param module 模块精确过滤，null 表示不过滤
     * @param action 动作精确过滤，null 表示不过滤
     * @param actorId 操作者精确过滤，null 表示不过滤
     * @param from 窗口起点（含），非空
     * @param to 窗口终点（不含），非空
     * @return 满足条件的总条数
     */
    long countSearch(
            String keyword, String module, String action, Long actorId, Instant from, Instant to);
}

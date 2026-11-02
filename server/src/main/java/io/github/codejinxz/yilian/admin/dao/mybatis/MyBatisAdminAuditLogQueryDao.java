package io.github.codejinxz.yilian.admin.dao.mybatis;

import io.github.codejinxz.yilian.admin.dao.AdminAuditLogQueryDao;
import io.github.codejinxz.yilian.admin.dao.AuditActivityRow;
import io.github.codejinxz.yilian.admin.service.vo.AdminAuditLogView;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** {@link AdminAuditLogQueryDao} 的 MyBatis 适配器；分区表只读，LIMIT/OFFSET 与时间窗口前置校验。 */
@Repository
public class MyBatisAdminAuditLogQueryDao implements AdminAuditLogQueryDao {

    private final AdminAuditLogQueryMapper mapper;

    /**
     * 注入 Mapper 代理。
     *
     * @param mapper MyBatis Mapper
     */
    public MyBatisAdminAuditLogQueryDao(AdminAuditLogQueryMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 取最近的管理活动。
     *
     * @param limit 最多返回条数，必须为正
     * @return 活动行
     * @throws IllegalArgumentException limit 非正时抛出（LIMIT 无守卫语义，契约前置校验）
     */
    @Override
    public List<AuditActivityRow> findRecent(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit 必须为正数: " + limit);
        }
        return mapper.findRecent(limit);
    }

    /**
     * 按条件检索操作日志。
     *
     * @param keyword 拼串模糊匹配（已转义），null 表示不过滤
     * @param module 模块精确过滤，null 表示不过滤
     * @param action 动作精确过滤，null 表示不过滤
     * @param actorId 操作者精确过滤，null 表示不过滤
     * @param from 窗口起点（含），非空
     * @param to 窗口终点（不含），非空
     * @param offset 偏移量，从 0 起
     * @param limit 每页条数，1–100
     * @return 当前页日志行
     * @throws IllegalArgumentException limit 非正或 offset 为负、时间窗口缺失时抛出（契约前置校验）
     */
    @Override
    public List<AdminAuditLogView> search(
            String keyword,
            String module,
            String action,
            Long actorId,
            Instant from,
            Instant to,
            int offset,
            int limit) {
        requirePaging(from, to, offset, limit);
        return mapper.search(keyword, module, action, actorId, from, to, offset, limit);
    }

    /**
     * 按条件统计操作日志总数。
     *
     * @param keyword 拼串模糊匹配（已转义），null 表示不过滤
     * @param module 模块精确过滤，null 表示不过滤
     * @param action 动作精确过滤，null 表示不过滤
     * @param actorId 操作者精确过滤，null 表示不过滤
     * @param from 窗口起点（含），非空
     * @param to 窗口终点（不含），非空
     * @return 总条数
     * @throws IllegalArgumentException 时间窗口缺失时抛出（契约前置校验）
     */
    @Override
    public long countSearch(
            String keyword, String module, String action, Long actorId, Instant from, Instant to) {
        requireRange(from, to);
        return mapper.countSearch(keyword, module, action, actorId, from, to);
    }

    /**
     * 分页与时间窗口契约校验：时间窗口是分区键条件，绝不允许缺失导致全分区扫描。
     *
     * @param from 窗口起点
     * @param to 窗口终点
     * @param offset 偏移量
     * @param limit 每页条数
     */
    private static void requirePaging(Instant from, Instant to, int offset, int limit) {
        requireRange(from, to);
        if (offset < 0) {
            throw new IllegalArgumentException("offset 不能为负数: " + offset);
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit 必须在 1–100 之间: " + limit);
        }
    }

    /**
     * 时间窗口契约校验。
     *
     * @param from 窗口起点
     * @param to 窗口终点
     */
    private static void requireRange(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException("时间窗口必须非空且 from 早于 to");
        }
    }
}

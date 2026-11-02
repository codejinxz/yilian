package io.github.codejinxz.yilian.admin.dao.mybatis;

import io.github.codejinxz.yilian.admin.dao.AuditActivityRow;
import io.github.codejinxz.yilian.admin.service.vo.AdminAuditLogView;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * 管理活动查询 Mapper：SQL 在 {@code mapper/AdminAuditLogQueryMapper.xml}。
 *
 * <p>实现细节，业务代码不要注入本接口——注入 {@code AdminAuditLogQueryDao} 契约。
 */
@Mapper
public interface AdminAuditLogQueryMapper {

    /**
     * 取最近的管理活动。
     *
     * @param limit 最多返回条数
     * @return 活动行，按 created_at 倒序
     */
    List<AuditActivityRow> findRecent(@Param("limit") int limit);

    /**
     * 按条件检索操作日志（当前页）。
     *
     * @param keyword 拼串模糊匹配（已转义），null 表示不过滤
     * @param module 模块精确过滤，null 表示不过滤
     * @param action 动作精确过滤，null 表示不过滤
     * @param actorId 操作者精确过滤，null 表示不过滤
     * @param from 窗口起点（含），非空
     * @param to 窗口终点（不含），非空
     * @param offset 偏移量
     * @param limit 每页条数
     * @return 当前页日志行
     */
    List<AdminAuditLogView> search(
            @Param("keyword") String keyword,
            @Param("module") String module,
            @Param("action") String action,
            @Param("actorId") Long actorId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("offset") int offset,
            @Param("limit") int limit);

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
     */
    long countSearch(
            @Param("keyword") String keyword,
            @Param("module") String module,
            @Param("action") String action,
            @Param("actorId") Long actorId,
            @Param("from") Instant from,
            @Param("to") Instant to);
}

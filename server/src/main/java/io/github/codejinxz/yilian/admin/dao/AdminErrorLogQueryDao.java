package io.github.codejinxz.yilian.admin.dao;

import io.github.codejinxz.yilian.admin.service.vo.AdminErrorLogDetailView;
import io.github.codejinxz.yilian.admin.service.vo.AdminErrorLogView;

import java.time.Instant;
import java.util.List;

/**
 * 异常日志只读查询 Dao（MyBatis 实现）。
 *
 * <p>{@code error_log} 是 V5 起的按月分区表（主键含 created_at），没有 JPA 实体； 检索与详情都走本 Dao 只读。列表查询不取 {@code
 * stack_trace}（最长 16KB）， 堆栈只在详情语句出现；时间范围是分区键条件，缺省窗口由服务层兜底近 30 天。
 */
public interface AdminErrorLogQueryDao {

    /**
     * 按条件检索异常日志（不含堆栈），按 created_at 倒序、同刻再按 id 倒序保证稳定。
     *
     * <p>keyword 在服务层已做 LIKE 通配转义，本 Dao 不再加工，直接传给 SQL 拼串 contains
     * （exception_class/message/trace_id/operator）。
     *
     * @param keyword 拼串模糊匹配，null 表示不过滤
     * @param module 模块精确过滤，null 表示不过滤
     * @param from 窗口起点（含），非空
     * @param to 窗口终点（不含），非空
     * @param offset 偏移量，从 0 起
     * @param limit 每页条数，1–100
     * @return 当前页日志行（不含堆栈）
     */
    List<AdminErrorLogView> search(
            String keyword, String module, Instant from, Instant to, int offset, int limit);

    /**
     * 与 {@link #search} 同条件的总数；分页元数据用。
     *
     * @param keyword 拼串模糊匹配，null 表示不过滤
     * @param module 模块精确过滤，null 表示不过滤
     * @param from 窗口起点（含），非空
     * @param to 窗口终点（不含），非空
     * @return 满足条件的总条数
     */
    long countSearch(String keyword, String module, Instant from, Instant to);

    /**
     * 按 id 取异常详情（含堆栈）。
     *
     * @param id 异常行 id
     * @return 详情行；不存在返回 null（服务层转 40400）
     */
    AdminErrorLogDetailView findById(long id);
}

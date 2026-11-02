package io.github.codejinxz.yilian.admin.dao.mybatis;

import io.github.codejinxz.yilian.admin.service.vo.AdminErrorLogDetailView;
import io.github.codejinxz.yilian.admin.service.vo.AdminErrorLogView;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

/**
 * 异常日志查询 Mapper：SQL 在 {@code mapper/AdminErrorLogQueryMapper.xml}。
 *
 * <p>实现细节，业务代码不要注入本接口——注入 {@code AdminErrorLogQueryDao} 契约。
 */
@Mapper
public interface AdminErrorLogQueryMapper {

    /**
     * 按条件检索异常日志（当前页，不含堆栈）。
     *
     * @param keyword 拼串模糊匹配（已转义），null 表示不过滤
     * @param module 模块精确过滤，null 表示不过滤
     * @param from 窗口起点（含），非空
     * @param to 窗口终点（不含），非空
     * @param offset 偏移量
     * @param limit 每页条数
     * @return 当前页日志行
     */
    List<AdminErrorLogView> search(
            @Param("keyword") String keyword,
            @Param("module") String module,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("offset") int offset,
            @Param("limit") int limit);

    /**
     * 按条件统计异常日志总数。
     *
     * @param keyword 拼串模糊匹配（已转义），null 表示不过滤
     * @param module 模块精确过滤，null 表示不过滤
     * @param from 窗口起点（含），非空
     * @param to 窗口终点（不含），非空
     * @return 总条数
     */
    long countSearch(
            @Param("keyword") String keyword,
            @Param("module") String module,
            @Param("from") Instant from,
            @Param("to") Instant to);

    /**
     * 按 id 取异常详情（含堆栈）。
     *
     * @param id 异常行 id
     * @return 详情行；不存在返回 null
     */
    AdminErrorLogDetailView findById(@Param("id") long id);
}

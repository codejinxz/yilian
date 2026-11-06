package io.github.codejinxz.yilian.practice.dao;

import io.github.codejinxz.yilian.practice.service.vo.AdminAppealView;

import java.util.List;

/**
 * 管理端申诉只读查询 Dao（MyBatis 实现）：列表 JOIN 题目摘要与申诉人，零物理外键下按逻辑关联取数。
 *
 * <p>complex query 归 MyBatis（§3.1）：分页 + 双 JOIN + JSON 列抽取；写路径（终审）仍走 JPA 的定向 UPDATE。
 */
public interface AppealAdminQueryDao {

    /**
     * 按条件检索申诉（created_at 倒序、同刻再按 id 倒序保证稳定）。
     *
     * @param status 状态精确过滤（PENDING/ACCEPTED/REJECTED），null 不过滤
     * @param kind 类型精确过滤（APPEAL/DISPUTE），null 不过滤
     * @param offset 偏移量，从 0 起
     * @param limit 每页条数，1–100
     * @return 当前页申诉行
     */
    List<AdminAppealView> search(String status, String kind, int offset, int limit);

    /**
     * 与 {@link #search} 同条件的总数；分页元数据用。
     *
     * @param status 状态过滤，null 不过滤
     * @param kind 类型过滤，null 不过滤
     * @return 满足条件的总条数
     */
    long countSearch(String status, String kind);

    /**
     * 按 id 取申诉行（终审后回读组装响应）。
     *
     * @param id 申诉行 id
     * @return 申诉行；不存在为 null（服务层转 40400）
     */
    AdminAppealView findById(long id);
}

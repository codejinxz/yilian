package io.github.codejinxz.yilian.content.dao;

import java.util.List;

/**
 * 管理端题目列表查询 Dao（只读）：sentences JOIN 当前内容快照，跨题库筛选分页。
 *
 * <p>管理端需要按快照列（zh/ref/level）过滤与展示，JPA 单表查询覆盖不了，按 AGENTS §3.1 归 MyBatis； 业务代码只依赖本接口，不注入 Mapper。
 */
public interface AdminSentenceQueryDao {

    /**
     * 分页检索管理端题目列表，按创建时间倒序。
     *
     * @param bankId 题库过滤，null 表示跨题库
     * @param keyword 关键字，匹配中文/参考译文，null 表示不过滤
     * @param level 难度精确过滤，null 表示不过滤
     * @param status 状态精确过滤，null 表示不过滤
     * @param hideRecycled true 时额外隐藏回收站（跨题库列表缺省视图）
     * @param offset 偏移量，从 0 开始
     * @param limit 每页条数，必须为正
     * @return 当前页行
     */
    List<AdminSentenceRow> search(
            Long bankId,
            String keyword,
            String level,
            String status,
            boolean hideRecycled,
            int offset,
            int limit);

    /**
     * 与 {@link #search} 同条件的总数，供 PageResponse 组装。
     *
     * @param bankId 题库过滤，null 表示跨题库
     * @param keyword 关键字，null 表示不过滤
     * @param level 难度精确过滤，null 表示不过滤
     * @param status 状态精确过滤，null 表示不过滤
     * @param hideRecycled true 时额外排除回收站
     * @return 满足条件的总行数
     */
    long count(Long bankId, String keyword, String level, String status, boolean hideRecycled);
}

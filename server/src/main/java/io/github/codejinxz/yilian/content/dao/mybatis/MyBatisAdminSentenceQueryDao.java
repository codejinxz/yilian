package io.github.codejinxz.yilian.content.dao.mybatis;

import io.github.codejinxz.yilian.content.dao.AdminSentenceQueryDao;
import io.github.codejinxz.yilian.content.dao.AdminSentenceRow;

import org.springframework.stereotype.Repository;

import java.util.List;

/** {@link AdminSentenceQueryDao} 的 MyBatis 适配器；LIMIT 前置校验。 */
@Repository
public class MyBatisAdminSentenceQueryDao implements AdminSentenceQueryDao {

    private final AdminSentenceQueryMapper mapper;

    /**
     * 注入 Mapper 代理。
     *
     * @param mapper MyBatis Mapper
     */
    public MyBatisAdminSentenceQueryDao(AdminSentenceQueryMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 分页检索管理端题目列表。
     *
     * @param bankId 题库过滤，可为 null
     * @param keyword 关键字，可为 null
     * @param level 难度过滤，可为 null
     * @param status 状态过滤，可为 null
     * @param hideRecycled 是否额外隐藏回收站
     * @param offset 偏移量，从 0 开始
     * @param limit 每页条数，必须为正
     * @return 当前页行
     * @throws IllegalArgumentException limit 非正或 offset 为负时抛出（LIMIT/OFFSET 无守卫语义，契约前置校验）
     */
    @Override
    public List<AdminSentenceRow> search(
            Long bankId,
            String keyword,
            String level,
            String status,
            boolean hideRecycled,
            int offset,
            int limit) {
        if (limit < 1 || offset < 0) {
            throw new IllegalArgumentException("分页参数不合法: offset=" + offset + ", limit=" + limit);
        }
        return mapper.search(bankId, keyword, level, status, hideRecycled, offset, limit);
    }

    /**
     * 与 {@link #search} 同条件的总数。
     *
     * @param bankId 题库过滤，可为 null
     * @param keyword 关键字，可为 null
     * @param level 难度过滤，可为 null
     * @param status 状态过滤，可为 null
     * @param hideRecycled 是否额外排除回收站
     * @return 总行数
     */
    @Override
    public long count(
            Long bankId, String keyword, String level, String status, boolean hideRecycled) {
        return mapper.count(bankId, keyword, level, status, hideRecycled);
    }
}

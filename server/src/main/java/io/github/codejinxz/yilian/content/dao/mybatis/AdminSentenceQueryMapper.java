package io.github.codejinxz.yilian.content.dao.mybatis;

import io.github.codejinxz.yilian.content.dao.AdminSentenceRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 管理端题目列表查询 Mapper：SQL 在 {@code mapper/AdminSentenceQueryMapper.xml}。
 *
 * <p>实现细节，业务代码不要注入本接口——注入 {@code AdminSentenceQueryDao} 契约。
 */
@Mapper
public interface AdminSentenceQueryMapper {

    /**
     * 分页检索管理端题目列表。
     *
     * @param bankId 题库过滤，可为 null
     * @param keyword 关键字，可为 null
     * @param level 难度过滤，可为 null
     * @param status 状态过滤，可为 null
     * @param hideRecycled 是否额外隐藏回收站
     * @param offset 偏移量
     * @param limit 每页条数
     * @return 当前页行
     */
    List<AdminSentenceRow> search(
            @Param("bankId") Long bankId,
            @Param("keyword") String keyword,
            @Param("level") String level,
            @Param("status") String status,
            @Param("hideRecycled") boolean hideRecycled,
            @Param("offset") int offset,
            @Param("limit") int limit);

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
    long count(
            @Param("bankId") Long bankId,
            @Param("keyword") String keyword,
            @Param("level") String level,
            @Param("status") String status,
            @Param("hideRecycled") boolean hideRecycled);
}

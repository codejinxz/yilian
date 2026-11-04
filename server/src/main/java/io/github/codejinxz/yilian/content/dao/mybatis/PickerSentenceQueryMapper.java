package io.github.codejinxz.yilian.content.dao.mybatis;

import io.github.codejinxz.yilian.content.dao.PickerSentenceRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 练习选题句列表 Mapper：SQL 在 {@code mapper/PickerSentenceQueryMapper.xml}。
 *
 * <p>实现细节，业务代码不要注入本接口——注入 {@code PickerSentenceQueryDao} 契约。
 */
@Mapper
public interface PickerSentenceQueryMapper {

    /**
     * 按动态筛选取题库内选题句（XML 动态 SQL）。
     *
     * @param bankId 题库 id，必填
     * @param userId 当前用户 id，null 表示匿名
     * @param keyword 关键字（服务层已转义），null 表示不过滤
     * @param level 难度精确过滤，null 表示不过滤
     * @param status 状态精确过滤，null 表示不过滤
     * @param hideRecycled true 时隐藏回收站行
     * @param offset 偏移量
     * @param limit 每页条数
     * @return 当前行
     */
    List<PickerSentenceRow> search(
            @Param("bankId") long bankId,
            @Param("userId") Long userId,
            @Param("keyword") String keyword,
            @Param("level") String level,
            @Param("status") String status,
            @Param("hideRecycled") boolean hideRecycled,
            @Param("offset") int offset,
            @Param("limit") int limit);

    /**
     * 与 {@link #search} 同条件的总数。
     *
     * @param bankId 题库 id
     * @param userId 当前用户 id，null 表示匿名
     * @param keyword 关键字，null 表示不过滤
     * @param level 难度精确过滤，null 表示不过滤
     * @param status 状态精确过滤，null 表示不过滤
     * @param hideRecycled true 时排除回收站行
     * @return 总行数
     */
    long count(
            @Param("bankId") long bankId,
            @Param("userId") Long userId,
            @Param("keyword") String keyword,
            @Param("level") String level,
            @Param("status") String status,
            @Param("hideRecycled") boolean hideRecycled);
}

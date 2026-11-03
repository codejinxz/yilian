package io.github.codejinxz.yilian.content.dao.mybatis;

import io.github.codejinxz.yilian.content.dao.PracticeCandidateRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * 练习候选查询 Mapper：SQL 在 {@code mapper/PracticeCandidateQueryMapper.xml}。
 *
 * <p>实现细节，业务代码不要注入本接口——注入 {@code PracticeCandidateQueryDao} 契约。
 */
@Mapper
public interface PracticeCandidateQueryMapper {

    /**
     * 按动态筛选取练习候选（XML 动态 SQL）。
     *
     * @param userId 当前用户 id，null 表示匿名（仅公开题库参与候选）
     * @param bankId 限定题库，null 表示全部
     * @param excludeIds 排除题目集合，null 或空表示不排除
     * @param tag 考点筛选，null 表示不筛选
     * @param limit 最多返回条数
     * @return 候选行
     */
    List<PracticeCandidateRow> findCandidates(
            @Param("userId") Long userId,
            @Param("bankId") Long bankId,
            @Param("excludeIds") Collection<String> excludeIds,
            @Param("tag") String tag,
            @Param("limit") int limit);
}

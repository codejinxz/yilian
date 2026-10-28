package io.github.codejinxz.yilian.content.dao.mybatis;

import io.github.codejinxz.yilian.content.dao.PracticeCandidateRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
     * @param bankId 限定题库，null 表示全部
     * @param excludeSentenceId 排除题目，null 表示不排除
     * @param limit 最多返回条数
     * @return 候选行
     */
    List<PracticeCandidateRow> findCandidates(
            @Param("bankId") Long bankId,
            @Param("excludeSentenceId") String excludeSentenceId,
            @Param("limit") int limit);
}

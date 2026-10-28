package io.github.codejinxz.yilian.content.dao.mybatis;

import io.github.codejinxz.yilian.content.dao.PracticeCandidateQueryDao;
import io.github.codejinxz.yilian.content.dao.PracticeCandidateRow;

import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * {@link PracticeCandidateQueryDao} 的 MyBatis 适配器，与 {@code dao.jpa.JpaXxxDao} 模式对称： 契约在 {@code dao}
 * 包，实现在 {@code dao.mybatis}，业务层无感知。
 */
@Repository
public class MyBatisPracticeCandidateQueryDao implements PracticeCandidateQueryDao {

    private final PracticeCandidateQueryMapper mapper;

    /**
     * 注入 Mapper 代理。
     *
     * @param mapper MyBatis Mapper
     */
    public MyBatisPracticeCandidateQueryDao(PracticeCandidateQueryMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 取练习候选。
     *
     * @param bankId 限定题库，可为 null
     * @param excludeSentenceId 排除题目，可为 null
     * @param limit 最多返回条数，必须为正
     * @return 候选行
     * @throws IllegalArgumentException limit 非正时抛出（LIMIT 无守卫语义，契约前置校验）
     */
    @Override
    public List<PracticeCandidateRow> findCandidates(
            Long bankId, String excludeSentenceId, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit 必须为正数: " + limit);
        }
        return mapper.findCandidates(bankId, excludeSentenceId, limit);
    }
}

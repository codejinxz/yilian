package io.github.codejinxz.yilian.content.dao.mybatis;

import io.github.codejinxz.yilian.content.dao.PracticeCandidateQueryDao;
import io.github.codejinxz.yilian.content.dao.PracticeCandidateRow;

import org.springframework.stereotype.Repository;

import java.util.Collection;
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
     * 取当前用户可练且未入队的新题候选。
     *
     * @param userId 当前用户 id，可为 null（匿名）
     * @param bankId 限定题库，可为 null
     * @param excludeIds 排除题目集合，可为 null
     * @param tag 考点筛选，可为 null
     * @param limit 最多返回条数，必须为正
     * @return 候选行
     * @throws IllegalArgumentException limit 非正时抛出（LIMIT 无守卫语义，契约前置校验）
     */
    @Override
    public List<PracticeCandidateRow> findCandidates(
            Long userId, Long bankId, Collection<String> excludeIds, String tag, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit 必须为正数: " + limit);
        }
        return mapper.findCandidates(userId, bankId, excludeIds, tag, limit);
    }
}

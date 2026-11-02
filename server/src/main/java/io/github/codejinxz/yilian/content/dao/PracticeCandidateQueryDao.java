package io.github.codejinxz.yilian.content.dao;

import java.util.Collection;
import java.util.List;

/**
 * 练习候选只读查询 Dao（MyBatis 实现，AGENTS §3.1「MyBatis 负责复杂查询」）。
 *
 * <p>单条 JOIN 直出可练候选：sentence_banks（发布且未删）× sentences（发布且未删）× 当前快照，替代原先「两条 JPA 查询 +
 * 内存匹配」的组合；业务代码只依赖本接口， 实现在 {@code dao.mybatis}（Mapper + 适配器）。
 */
public interface PracticeCandidateQueryDao {

    /**
     * 取练习候选，按创建时间稳定排序（created_at 升序、id 升序）。
     *
     * <p>与被替换的两步 JPA 实现的边界差：先 JOIN 丢弃快照缺失行、再应用 limit， 因此缺快照行不再占用 limit 名额（返回的可用行只会更多，不会更少）。
     *
     * @param bankId 限定题库，null 表示全部可练题库
     * @param excludeIds 排除的题目 id 集合，null 或空表示不排除
     * @param tag 考点筛选（tags JSON 精确成员匹配），null 表示不筛选
     * @param limit 最多返回条数，必须为正
     * @return 候选行；无可练题目时为空列表
     */
    List<PracticeCandidateRow> findCandidates(
            Long bankId, Collection<String> excludeIds, String tag, int limit);
}

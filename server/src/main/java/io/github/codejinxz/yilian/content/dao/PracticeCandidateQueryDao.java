package io.github.codejinxz.yilian.content.dao;

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
     * @param bankId 限定题库，null 表示全部可练题库
     * @param excludeSentenceId 排除的题目 id，null 表示不排除
     * @param limit 最多返回条数，必须为正
     * @return 候选行；无可练题目时为空列表
     */
    List<PracticeCandidateRow> findCandidates(Long bankId, String excludeSentenceId, int limit);
}

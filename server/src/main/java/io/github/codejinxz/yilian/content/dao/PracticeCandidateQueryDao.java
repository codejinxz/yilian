package io.github.codejinxz.yilian.content.dao;

import java.util.Collection;
import java.util.List;

/**
 * 练习候选只读查询 Dao（MyBatis 实现，AGENTS §3.1「MyBatis 负责复杂查询」）。
 *
 * <p>单条 JOIN 直出「当前用户可练且未入队」的候选：sentence_banks（发布且未删、公开或本人私有）× sentences（发布且未删）× 当前快照 × 无非 NEW
 * 复习卡；可见性在数据层过滤（016 P0-1a），「未入队」下推 NOT EXISTS（016 P1，防止 LIMIT 窗口被已练句子占满）； 业务代码只依赖本接口，实现在 {@code
 * dao.mybatis}（Mapper + 适配器）。
 */
public interface PracticeCandidateQueryDao {

    /**
     * 取当前用户可练且未入队的新题候选，按创建时间稳定排序（created_at 升序、id 升序）。
     *
     * <p>边界语义：先过滤可见性/未入队/排除集/快照缺失行、再应用 limit， 因此被过滤行不占用 limit 名额（返回的可用行只会更多，不会更少）。
     *
     * @param userId 当前用户 id，null 表示匿名（仅公开题库参与候选，且无复习卡可排除）
     * @param bankId 限定题库，null 表示全部可练题库
     * @param excludeIds 排除的题目 id 集合，null 或空表示不排除
     * @param tag 考点筛选（tags JSON 精确成员匹配），null 表示不筛选
     * @param limit 最多返回条数，必须为正
     * @return 候选行；无可练题目时为空列表
     */
    List<PracticeCandidateRow> findCandidates(
            Long userId, Long bankId, Collection<String> excludeIds, String tag, int limit);
}

package io.github.codejinxz.yilian.content.dao;

import java.util.List;

/**
 * 练习选题句列表查询 Dao（只读）：sentences JOIN 当前快照 LEFT JOIN 当前用户复习卡，题库内分页。
 *
 * <p>练习面板需要按快照列（zh/level）过滤并逐行携带练过态，JPA 单表查询覆盖不了，归 MyBatis；业务代码只依赖本接口，不注入 Mapper。 练过态经 SQL 内 JOIN
 * reviews 取得，不跨模块引入 practice 的 Java 类型（与 {@link PracticeCandidateQueryDao} 同口径）。
 */
public interface PickerSentenceQueryDao {

    /**
     * 分页检索题库内选题句，按创建时间升序（题库录入序，与增量同步端点一致）。
     *
     * @param bankId 题库 id，必填（调用方先做可见性校验）
     * @param userId 当前用户 id，null 表示匿名（练过态两列恒 NULL）
     * @param keyword 关键字（服务层已 escapeLike），匹配中文/参考译文，null 表示不过滤
     * @param level 难度精确过滤，null 表示不过滤
     * @param status 状态精确过滤，null 表示不过滤（此时由 hideRecycled 决定回收站是否隐藏）
     * @param hideRecycled true 时隐藏回收站行（缺省视图）
     * @param offset 偏移量，从 0 开始
     * @param limit 每页条数，必须为正
     * @return 当前页行
     */
    List<PickerSentenceRow> search(
            long bankId,
            Long userId,
            String keyword,
            String level,
            String status,
            boolean hideRecycled,
            int offset,
            int limit);

    /**
     * 与 {@link #search} 同条件的总数，供分页组装。
     *
     * @param bankId 题库 id
     * @param userId 当前用户 id，null 表示匿名
     * @param keyword 关键字，null 表示不过滤
     * @param level 难度精确过滤，null 表示不过滤
     * @param status 状态精确过滤，null 表示不过滤
     * @param hideRecycled true 时排除回收站行
     * @return 满足条件的总行数
     */
    long count(
            long bankId,
            Long userId,
            String keyword,
            String level,
            String status,
            boolean hideRecycled);
}

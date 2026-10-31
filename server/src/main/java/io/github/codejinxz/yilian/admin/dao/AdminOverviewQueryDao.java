package io.github.codejinxz.yilian.admin.dao;

import java.time.Instant;
import java.util.List;

/**
 * 概览统计只读查询 Dao（MyBatis 实现，AGENTS §3.1「MyBatis 负责复杂查询/统计」）。
 *
 * <p>跨 users/attempts 两表的计数与按日分组统计；管理模块不依赖 practice/system 的实体， 全部走本 Dao 的聚合结果。时间入参均为 UTC Instant，
 * 与库内 DATETIME(6) 的 UTC 口径一致。
 */
public interface AdminOverviewQueryDao {

    /**
     * 注册用户总数（含已软删，口径为注册总量）。
     *
     * @return users 表行数
     */
    long countUsers();

    /**
     * 答题总数。
     *
     * @return attempts 表行数
     */
    long countAttempts();

    /**
     * 统计时间窗内有提交记录的独立用户数。
     *
     * @param from 起始时间（含，UTC）
     * @param to 截止时间（不含，UTC）
     * @return 去重用户数
     */
    long countActiveUsersBetween(Instant from, Instant to);

    /**
     * 按自然日（UTC）统计有提交记录的独立用户数；无记录的日期不返回行，由服务层补 0。
     *
     * @param from 起始时间（含，UTC）
     * @param to 截止时间（不含，UTC）
     * @return 按日期升序的计数行
     */
    List<DailyActiveCountRow> countDailyActiveUsersBetween(Instant from, Instant to);
}

package io.github.codejinxz.yilian.practice.dao;

import io.github.codejinxz.yilian.practice.entity.LlmUsage;

import java.time.Instant;
import java.util.List;

/** LLM 用量账本 Dao：append-only 写入 + 预算护栏的只读聚合（月度 SUM 与按场景 GROUP BY）。 */
public interface LlmUsageDao {

    /**
     * 追加一条用量记录；实现不抛出受检异常，调用方自行决定失败策略。
     *
     * @param usage 用量记录
     * @return 持久化后的记录
     */
    LlmUsage save(LlmUsage usage);

    /**
     * 累计估算成本（微元）：只取估算值非空的行，按记录时刻下界过滤（UTC 月初起算=月度口径）。 预算护栏的金额事实来源——直查不缓存（三调用点低频）。
     *
     * @param since 起始时刻（含）；传 UTC 月初即得本月累计
     * @return 累计微元；无满足条件的行时为 0
     */
    long sumEstimatedCostSince(Instant since);

    /**
     * 按场景聚合当月用量（token/成本/次数）。
     *
     * @param since 起始时刻（含）；传 UTC 月初即得本月聚合
     * @return 每个出现过的场景一行；空表无行
     */
    List<SceneUsageSummary> sumBySceneSince(Instant since);
}

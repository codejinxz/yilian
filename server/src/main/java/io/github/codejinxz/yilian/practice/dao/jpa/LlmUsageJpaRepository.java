package io.github.codejinxz.yilian.practice.dao.jpa;

import io.github.codejinxz.yilian.practice.dao.SceneUsageSummary;
import io.github.codejinxz.yilian.practice.entity.LlmUsage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/** LLM 用量 Spring Data 仓库，只允许同包 JpaLlmUsageDao 使用。 */
public interface LlmUsageJpaRepository extends JpaRepository<LlmUsage, Long> {

    /**
     * 累计估算成本（微元）：只聚合估算值非空的行（NULL 不计入预算语义在列定义层表达）。
     *
     * @param since 起始时刻（含）
     * @return 累计微元；无行时 COALESCE 兜 0
     */
    @Query(
            "SELECT COALESCE(SUM(u.estimatedCostMicroCny), 0) FROM LlmUsage u"
                    + " WHERE u.createdAt >= :since")
    long sumEstimatedCostSince(@Param("since") Instant since);

    /**
     * 按场景聚合用量：token/成本/次数三列一起算（单表 GROUP BY，构造器表达式直出投影； 复杂跨表统计才走 MyBatis，本聚合单表可表达，留 MyBatis
     * 席位给后续跨表报表）。
     *
     * @param since 起始时刻（含）
     * @return 每个出现过的场景一行
     */
    @Query(
            "SELECT new io.github.codejinxz.yilian.practice.dao.SceneUsageSummary(u.scene,"
                    + " COALESCE(SUM(u.promptTokens), 0), COALESCE(SUM(u.completionTokens), 0),"
                    + " COALESCE(SUM(u.estimatedCostMicroCny), 0), COUNT(u)) FROM LlmUsage u"
                    + " WHERE u.createdAt >= :since GROUP BY u.scene")
    List<SceneUsageSummary> sumBySceneSince(@Param("since") Instant since);
}

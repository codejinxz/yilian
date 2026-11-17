package io.github.codejinxz.yilian.practice.dao;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.codejinxz.yilian.practice.entity.LlmUsage;
import io.github.codejinxz.yilian.support.TestDatabases;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * V16 迁移落库形状与 llm_usage 成本列读写聚合集成测试（真实 MySQL，Flyway V1→V16 自动跑）[AC-1]。
 *
 * <p>锁定：estimated_cost_micro_cny 列形状（BIGINT/可空）、Dao 落库读回（成本随行落列，NULL 如实）、 月度 SUM 的 UTC
 * 月初过滤下界（上月落库行不计=跨月自然重置，[AC-4] 数据层证据）、按场景聚合投影 （token/成本/次数三列，NULL 兜零）。
 */
@SpringBootTest(
        properties = {
            "yilian.ratelimit.enabled=false",
            "yilian.security.captcha.enabled=false",
            "management.health.redis.enabled=false",
            "yilian.import.reconcile-enabled=false"
        })
@ActiveProfiles("test")
class V16LlmUsageCostSchemaIntegrationTest {

    /** 本类专用 scene 标识（清理键）。 */
    private static final String SCENE = "v16-shape";

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private LlmUsageDao llmUsageDao;

    static {
        TestDatabases.ensureDatabase();
    }

    /** 用例后清掉本类播种的用量行（append-only 表无更新，逐用例先摘）。 */
    @AfterEach
    void clean() {
        jdbcTemplate.update("DELETE FROM llm_usage WHERE scene = ?", SCENE);
    }

    /** [AC-1] 列形状：estimated_cost_micro_cny 为 BIGINT 且可空（无定价=NULL=不计入预算）。 */
    @Test
    @DisplayName("[AC-1] V16 列形状锁定（BIGINT/可空）")
    void ac1_columnShapeLocked() {
        Map<String, Object> column =
                jdbcTemplate.queryForMap(
                        "SELECT DATA_TYPE, IS_NULLABLE FROM information_schema.COLUMNS"
                                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'llm_usage'"
                                + " AND COLUMN_NAME = 'estimated_cost_micro_cny'");
        assertThat(column.get("DATA_TYPE")).isEqualTo("bigint");
        assertThat(column.get("IS_NULLABLE")).isEqualTo("YES");
    }

    /** [AC-1] Dao 落库读回：成本随行落列读回；NULL 成本行如实存 NULL（不落 0）。 */
    @Test
    @DisplayName("[AC-1] 成本列落库读回与 NULL 如实")
    void ac1_costPersistsAndStaysNullWhenAbsent() {
        Instant now = Instant.now();
        LlmUsage priced =
                llmUsageDao.save(
                        LlmUsage.of(
                                null,
                                null,
                                SCENE,
                                "openai-chat",
                                "glm-4.7",
                                120,
                                30,
                                900L,
                                480L,
                                true,
                                now));
        LlmUsage unpriced =
                llmUsageDao.save(
                        LlmUsage.of(
                                null,
                                null,
                                SCENE,
                                "openai-chat",
                                "no-price-model",
                                10,
                                2,
                                50L,
                                null,
                                true,
                                now));

        Long pricedCost =
                jdbcTemplate.queryForObject(
                        "SELECT estimated_cost_micro_cny FROM llm_usage WHERE id = ?",
                        Long.class,
                        priced.getId());
        assertThat(pricedCost).isEqualTo(480L);
        Long unpricedCost =
                jdbcTemplate.queryForObject(
                        "SELECT estimated_cost_micro_cny FROM llm_usage WHERE id = ?",
                        Long.class,
                        unpriced.getId());
        assertThat(unpricedCost).isNull();
    }

    /** [AC-4] 月度 SUM：UTC 月初下界——本月行（含多行累加）计入，上月行与 NULL 成本行不计。 */
    @Test
    @DisplayName("[AC-4] 月度 SUM 只聚合 UTC 月初起的非 NULL 成本行")
    void ac4_monthlySumExcludesPreviousMonthRows() {
        YearMonth month = YearMonth.now(ZoneOffset.UTC);
        Instant monthStart =
                io.github.codejinxz.yilian.practice.llm.BudgetGuardService.monthStartUtc(month);
        Instant currentMonth = month.atDay(15).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant previousMonth =
                month.minusMonths(1).atDay(15).atStartOfDay(ZoneOffset.UTC).toInstant();

        llmUsageDao.save(
                LlmUsage.of(
                        null, null, SCENE, "openai-chat", "m", 1, 1, 1L, 300L, true, currentMonth));
        llmUsageDao.save(
                LlmUsage.of(
                        null, null, SCENE, "openai-chat", "m", 1, 1, 1L, 200L, true, currentMonth));
        // 上月落库行：成本再高也不计入本月（跨月自然重置）。
        llmUsageDao.save(
                LlmUsage.of(
                        null,
                        null,
                        SCENE,
                        "openai-chat",
                        "m",
                        1,
                        1,
                        1L,
                        999_999L,
                        true,
                        previousMonth));
        // 本月无定价行：成本 NULL，不计入 SUM。
        llmUsageDao.save(
                LlmUsage.of(
                        null,
                        null,
                        SCENE,
                        "openai-chat",
                        "no-price",
                        1,
                        1,
                        1L,
                        null,
                        true,
                        currentMonth));

        long monthly = llmUsageDao.sumEstimatedCostSince(monthStart);
        assertThat(monthly).isEqualTo(500L);
        // 下界放宽到上月月初：上月行进入聚合（对照证据）。
        assertThat(llmUsageDao.sumEstimatedCostSince(monthStartUtc(month.minusMonths(1))))
                .isEqualTo(1_000_499L);
    }

    /** [AC-6] 按场景聚合：token/成本/次数三列一起算，NULL 兜零，场景间不串。 */
    @Test
    @DisplayName("[AC-6] 按场景聚合投影（token/成本/次数）")
    void ac6_sceneAggregation() {
        YearMonth month = YearMonth.now(ZoneOffset.UTC);
        Instant monthStart = monthStartUtc(month);
        Instant currentMonth = month.atDay(10).atStartOfDay(ZoneOffset.UTC).toInstant();

        llmUsageDao.save(
                LlmUsage.of(
                        null,
                        null,
                        SCENE,
                        "openai-chat",
                        "m",
                        100,
                        20,
                        1L,
                        480L,
                        true,
                        currentMonth));
        llmUsageDao.save(
                LlmUsage.of(
                        null,
                        null,
                        SCENE,
                        "openai-chat",
                        "m",
                        50,
                        30,
                        1L,
                        340L,
                        false,
                        currentMonth));
        // 失败行 token 缺位：聚合按 0 兜底，调用次数照计。
        llmUsageDao.save(
                LlmUsage.of(
                        null,
                        null,
                        SCENE,
                        "openai-chat",
                        "m",
                        null,
                        null,
                        1L,
                        null,
                        false,
                        currentMonth));
        llmUsageDao.save(
                LlmUsage.of(
                        null,
                        null,
                        "v16-shape-other",
                        "openai-chat",
                        "m",
                        7,
                        7,
                        1L,
                        21L,
                        true,
                        currentMonth));

        List<SceneUsageSummary> rows = llmUsageDao.sumBySceneSince(monthStart);

        assertThat(rows).extracting(SceneUsageSummary::scene).contains(SCENE, "v16-shape-other");
        SceneUsageSummary summary =
                rows.stream().filter(row -> SCENE.equals(row.scene())).findFirst().orElseThrow();
        assertThat(summary.totalTokens()).isEqualTo(200L);
        assertThat(summary.costMicro()).isEqualTo(820L);
        assertThat(summary.callCount()).isEqualTo(3L);
        SceneUsageSummary other =
                rows.stream()
                        .filter(row -> "v16-shape-other".equals(row.scene()))
                        .findFirst()
                        .orElseThrow();
        assertThat(other.totalTokens()).isEqualTo(14L);
        assertThat(other.costMicro()).isEqualTo(21L);
        assertThat(other.callCount()).isEqualTo(1L);
    }

    /**
     * 取 UTC 月初（测试内固定经由被测口径同源的辅助方法）。
     *
     * @param month UTC 月份
     * @return 月初 Instant
     */
    private static Instant monthStartUtc(YearMonth month) {
        return month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}

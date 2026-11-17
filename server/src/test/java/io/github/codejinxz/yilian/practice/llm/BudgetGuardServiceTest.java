package io.github.codejinxz.yilian.practice.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.codejinxz.yilian.practice.dao.LlmUsageDao;
import io.github.codejinxz.yilian.system.service.AppSettingService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

/**
 * {@link BudgetGuardService} 单测 [AC-4]：80%/100% 边界（79.99/80/99.99/100，整数微元精确比较）、 UTC 月初口径（传给 SUM
 * 的下界=当月 1 日 00:00 UTC）、无预算键直通（护栏不生效，不触用量表）。 Dao 与配置存取均 mock；「上月落库行不计」的数据层证据由 V16 schema 集成测试用真实
 * MySQL 锁定。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BudgetGuardServiceTest {

    @Mock private LlmUsageDao llmUsageDao;

    @Mock private AppSettingService appSettingService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private LlmBudgetSettings budgetSettings;

    private BudgetGuardService guard;

    @BeforeEach
    void setUp() {
        budgetSettings = new LlmBudgetSettings(appSettingService, objectMapper);
        guard = new BudgetGuardService(budgetSettings, llmUsageDao);
    }

    /**
     * 覆盖配置读取：按给定值生成 llm.budget 存储 JSON（null=未设键）。
     *
     * @param budgetCny 月度预算（元），null=未设
     */
    private void stubBudget(BigDecimal budgetCny) {
        when(appSettingService.findValue(LlmBudgetSettings.SETTING_KEY))
                .thenReturn(
                        budgetCny == null
                                ? Optional.empty()
                                : Optional.of(
                                        "{\"schemaVersion\":1,\"monthlyBudgetCny\":"
                                                + budgetCny.toPlainString()
                                                + ",\"pricing\":null}"));
    }

    /** [AC-4] 无预算键：直通 OK 且不触用量表（护栏不生效=现行为）。 */
    @Test
    @DisplayName("[AC-4] 无预算键直通且不触用量表")
    void passesThroughWithoutBudgetKey_ac4() {
        stubBudget(null);
        when(llmUsageDao.sumEstimatedCostSince(any(Instant.class))).thenReturn(999L);

        assertThat(guard.gate()).isEqualTo(BudgetGuardService.Status.OK);
        verifyNoInteractions(llmUsageDao);

        // snapshot（用量视图口径）始终实算已用：触库，但预算字段仍为 null。
        BudgetGuardService.Snapshot snapshot = guard.snapshot();
        assertThat(snapshot.hasBudget()).isFalse();
        assertThat(snapshot.budgetCny()).isNull();
        assertThat(snapshot.percent()).isNull();
        assertThat(snapshot.status()).isEqualTo(BudgetGuardService.Status.OK);
        assertThat(snapshot.spentMicroCny()).isEqualTo(999L);
        assertThat(snapshot.monthUtc()).isEqualTo(YearMonth.now(ZoneOffset.UTC));
    }

    /**
     * [AC-4] 80/100 边界（预算 100 元）：79.99% OK → 80% WARN → 99.99% WARN → 100% EXCEEDED。
     * 阈值比较在整数微元域精确执行（不做浮点除法）。
     */
    @Test
    @DisplayName("[AC-4] 79.99/80/99.99/100 边界三态判定")
    void evaluatesBoundaries_ac4() {
        stubBudget(new BigDecimal("100"));
        long budgetMicro = 100_000_000L;
        when(llmUsageDao.sumEstimatedCostSince(any(Instant.class))).thenReturn(79_990_000L);
        assertThat(guard.gate()).isEqualTo(BudgetGuardService.Status.OK);

        when(llmUsageDao.sumEstimatedCostSince(any(Instant.class))).thenReturn(80_000_000L);
        assertThat(guard.gate()).isEqualTo(BudgetGuardService.Status.WARN);

        when(llmUsageDao.sumEstimatedCostSince(any(Instant.class))).thenReturn(99_990_000L);
        assertThat(guard.gate()).isEqualTo(BudgetGuardService.Status.WARN);

        when(llmUsageDao.sumEstimatedCostSince(any(Instant.class))).thenReturn(budgetMicro);
        assertThat(guard.gate()).isEqualTo(BudgetGuardService.Status.EXCEEDED);
    }

    /** [AC-4] snapshot 百分比四舍五入：79.99% → 80；超支如实给 120 且状态 EXCEEDED。 */
    @Test
    @DisplayName("[AC-4] 百分比取整与超支如实")
    void snapshotPercentRounds_ac4() {
        stubBudget(new BigDecimal("100"));
        when(llmUsageDao.sumEstimatedCostSince(any(Instant.class))).thenReturn(79_990_000L);
        assertThat(guard.snapshot().percent()).isEqualTo(80);

        when(llmUsageDao.sumEstimatedCostSince(any(Instant.class))).thenReturn(120_000_000L);
        BudgetGuardService.Snapshot over = guard.snapshot();
        assertThat(over.status()).isEqualTo(BudgetGuardService.Status.EXCEEDED);
        assertThat(over.percent()).isEqualTo(120);
        assertThat(over.budgetCny()).isEqualByComparingTo(new BigDecimal("100"));
    }

    /** [AC-4] 月度口径：SUM 过滤下界=当月 1 日 00:00 UTC（跨月自然重置）。 */
    @Test
    @DisplayName("[AC-4] SUM 下界为 UTC 月初")
    void sumsFromUtcMonthStart_ac4() {
        stubBudget(new BigDecimal("50"));
        java.util.concurrent.atomic.AtomicReference<Instant> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(llmUsageDao.sumEstimatedCostSince(any(Instant.class)))
                .thenAnswer(
                        invocation -> {
                            captured.set(invocation.getArgument(0));
                            return 0L;
                        });

        guard.snapshot();

        Instant expected = BudgetGuardService.monthStartUtc(YearMonth.now(ZoneOffset.UTC));
        assertThat(captured.get()).isEqualTo(expected);
    }

    /** [AC-4] 配置里的定价对护栏判定无影响（定价只作用于记账成本，判定只看预算与 SUM）。 */
    @Test
    @DisplayName("[AC-4] 判定只看预算与已用，与定价无关")
    void ignoresPricingForGate_ac4() {
        when(appSettingService.findValue(LlmBudgetSettings.SETTING_KEY))
                .thenReturn(
                        Optional.of(
                                "{\"schemaVersion\":1,\"monthlyBudgetCny\":10,\"pricing\":"
                                        + "{\"glm-4.7\":{\"inputPerMillionCny\":2,"
                                        + "\"outputPerMillionCny\":8}}}"));
        when(llmUsageDao.sumEstimatedCostSince(any(Instant.class))).thenReturn(0L);

        BudgetGuardService.Snapshot snapshot = guard.snapshot();

        assertThat(snapshot.status()).isEqualTo(BudgetGuardService.Status.OK);
        assertThat(snapshot.spentMicroCny()).isZero();
        // 定价经 fail-closed 解析进入配置快照（写路径形状锁定另由 LlmBudgetSettingsTest 承担）。
        Map<String, LlmModelPricing> pricing = budgetSettings.read().pricing();
        assertThat(pricing).containsKey("glm-4.7");
    }
}

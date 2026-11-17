package io.github.codejinxz.yilian.practice.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link LlmCostCalculator} 纯函数单测：输入/输出分开计价、四舍五入口径固定（整体一次 ROUND_HALF_UP）、定价缺失与退化输入的
 * NULL/零语义。全整数断言（微元），不引入浮点比较。
 */
class LlmCostCalculatorTest {

    /** 定价夹具：输入 2 元/百万 token、输出 8 元/百万 token。 */
    private static final LlmModelPricing PRICING =
            new LlmModelPricing(new BigDecimal("2"), new BigDecimal("8"));

    /** 定价表夹具：单模型 glm-4.7。 */
    private static final Map<String, LlmModelPricing> PRICING_MAP = Map.of("glm-4.7", PRICING);

    /** [AC-3] 输入/输出分开计价：1M 输入 + 0.5M 输出 = 2 + 4 = 6 元 = 6_000_000 微元。 */
    @Test
    @DisplayName("[AC-3] 输入/输出按各自单价分开计价后求和")
    void pricesInputAndOutputSeparately_ac3() {
        Long micro = LlmCostCalculator.estimateMicroCny("glm-4.7", 1_000_000, 500_000, PRICING_MAP);

        assertThat(micro).isEqualTo(6_000_000L);
    }

    /** [AC-3] 常规量级：120 输入 + 30 输出 token，成本 2×120/1e6 + 8×30/1e6 = 0.00048 元 = 480 微元。 */
    @Test
    @DisplayName("[AC-3] 常规 token 量级按微元如实落值")
    void computesRegularMagnitude_ac3() {
        Long micro = LlmCostCalculator.estimateMicroCny("glm-4.7", 120, 30, PRICING_MAP);

        assertThat(micro).isEqualTo(480L);
    }

    /** [AC-3] 舍入口径固定：合计后整体一次 ROUND_HALF_UP（0.5 微元进一），不逐项舍入。 */
    @Test
    @DisplayName("[AC-3] 四舍五入在合计后整体一次执行")
    void roundsHalfUpOnceOnTotal_ac3() {
        // 1 输入 token = 2/1e6 元 = 2 微元；1 输出 token = 8/1e6 元 = 8 微元。
        // 定价 0.000025 元/百万 输出 → 1 输出 token = 0.025 微元；凑 0.5 微元边界：40 输出 = 1.0？改用精确构造：
        // 输入单价 0.3 元/百万：1 token = 0.3 微元；50 token = 15 微元。输出单价 0.7：30 token = 21 微元。合计 36。
        LlmModelPricing fractional =
                new LlmModelPricing(new BigDecimal("0.3"), new BigDecimal("0.7"));
        Map<String, LlmModelPricing> map = Map.of("m", fractional);

        // 7 输入 = 2.1 微元 + 5 输出 = 3.5 微元 → 合计 5.6 → ROUND_HALF_UP = 6。
        assertThat(LlmCostCalculator.estimateMicroCny("m", 7, 5, map)).isEqualTo(6L);
        // 3 输入 = 0.9 微元 + 2 输出 = 1.4 微元 → 合计 2.3 → ROUND_HALF_UP = 2。
        assertThat(LlmCostCalculator.estimateMicroCny("m", 3, 2, map)).isEqualTo(2L);
    }

    /** [AC-3] 缺模型/定价表为 null/模型名为 null：一律 null=不计入预算（fail-open 不拦调用）。 */
    @Test
    @DisplayName("[AC-3] 无定价返回 null（不计入预算）")
    void returnsNullWhenPricingMissing_ac3() {
        assertThat(LlmCostCalculator.estimateMicroCny("unknown-model", 100, 20, PRICING_MAP))
                .isNull();
        assertThat(LlmCostCalculator.estimateMicroCny("glm-4.7", 100, 20, null)).isNull();
        assertThat(LlmCostCalculator.estimateMicroCny(null, 100, 20, PRICING_MAP)).isNull();
        // 定价条目一侧单价缺失：视为无效定价，同样 null。
        Map<String, LlmModelPricing> halfPricing =
                Map.of("glm-4.7", new LlmModelPricing(new BigDecimal("2"), null));
        assertThat(LlmCostCalculator.estimateMicroCny("glm-4.7", 100, 20, halfPricing)).isNull();
        Map<String, LlmModelPricing> negativePricing =
                Map.of("glm-4.7", new LlmModelPricing(new BigDecimal("2"), BigDecimal.ZERO));
        assertThat(LlmCostCalculator.estimateMicroCny("glm-4.7", 100, 20, negativePricing))
                .isNull();
    }

    /** [AC-3] 零 token 与 null token：已定价模型按 0 微元计（成本如实为零而非不计入）。 */
    @Test
    @DisplayName("[AC-3] 零/null token 计 0 微元")
    void zeroTokensYieldZeroMicro_ac3() {
        assertThat(LlmCostCalculator.estimateMicroCny("glm-4.7", 0, 0, PRICING_MAP)).isEqualTo(0L);
        assertThat(LlmCostCalculator.estimateMicroCny("glm-4.7", null, null, PRICING_MAP))
                .isEqualTo(0L);
        assertThat(LlmCostCalculator.estimateMicroCny("glm-4.7", null, 10, PRICING_MAP))
                .isEqualTo(80L);
    }

    /** [AC-3] 定价表键区分大小写（与 llm_usage.model 快照精确匹配），未命中按无定价处理。 */
    @Test
    @DisplayName("[AC-3] 模型名精确匹配区分大小写")
    void matchesModelNameExactly_ac3() {
        Map<String, LlmModelPricing> map = new HashMap<>();
        map.put("GLM-4.7", PRICING);

        assertThat(LlmCostCalculator.estimateMicroCny("GLM-4.7", 1_000_000, 0, map))
                .isEqualTo(2_000_000L);
        assertThat(LlmCostCalculator.estimateMicroCny("glm-4.7", 1_000_000, 0, map)).isNull();
    }
}

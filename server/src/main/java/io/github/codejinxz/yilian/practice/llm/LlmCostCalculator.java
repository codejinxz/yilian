package io.github.codejinxz.yilian.practice.llm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * LLM 调用成本估算纯函数（预算护栏）：按当次模型定价把 token 用量换算为人民币微元。
 *
 * <p>口径固定：{@code 成本(元) = 输入token × inputPerMillionCny / 1e6 + 输出token × outputPerMillionCny / 1e6}，
 * 微元 = 成本(元) × 1e6，即 <strong>输入单价 × 输入token + 输出单价 × 输出token</strong>（除以与乘回 1e6 相消， 全 BigDecimal
 * 无浮点误差），合计后 <strong>整体一次</strong> ROUND_HALF_UP 取整——先分后舍，不做逐项舍入，
 * 保证同一输入在任何调用点得到同一个整数。模型无定价（未配置/条目无效）返回 null=不计入预算 （fail-open：护栏只对已定价模型生效，定价缺失不拦调用只记 NULL，如实免费语义）。
 *
 * <p>本类不做 I/O、不持有状态：三处记账点（答疑/复核/初裁）在落库前各调一次，输入完全决定输出。
 */
public final class LlmCostCalculator {

    private LlmCostCalculator() {
        // 纯函数工具类，不实例化
    }

    /**
     * 估算一次调用的成本（微元整数）。
     *
     * @param model 当次模型名快照（与定价表键精确匹配）；null 视为无定价
     * @param promptTokens 提示词 token；null 按 0 计（失败路径未取得用量），负数防御性钳为 0（provider 侧理论不可能，防脏数据放大成负成本）
     * @param completionTokens 回复 token；null 按 0 计，负数防御性钳为 0
     * @param pricing 定价表（模型名 → 单价）；null 视为空表
     * @return 估算成本微元（ROUND_HALF_UP）；该模型无有效定价时为 null（不计入预算）
     */
    public static Long estimateMicroCny(
            String model,
            Integer promptTokens,
            Integer completionTokens,
            Map<String, LlmModelPricing> pricing) {
        if (model == null || pricing == null) {
            return null;
        }
        LlmModelPricing modelPricing = pricing.get(model);
        if (modelPricing == null || !modelPricing.isValid()) {
            return null;
        }
        long prompt = promptTokens == null ? 0L : Math.max(0, promptTokens.longValue());
        long completion = completionTokens == null ? 0L : Math.max(0, completionTokens.longValue());
        // 微元 = 输入单价 × 输入token + 输出单价 × 输出token（见类注：成本(元)×1e6 的相消恒等式）。
        BigDecimal microCny =
                modelPricing
                        .inputPerMillionCny()
                        .multiply(BigDecimal.valueOf(prompt))
                        .add(
                                modelPricing
                                        .outputPerMillionCny()
                                        .multiply(BigDecimal.valueOf(completion)));
        return microCny.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}

package io.github.codejinxz.yilian.practice.llm;

import java.math.BigDecimal;

/**
 * 单模型定价：每百万 token 的输入/输出人民币单价（元）。
 *
 * <p>预算与成本口径的共享形状：管理端配置请求/回显视图、app_settings 键 {@code llm.budget} 存储值、 成本估算（{@link
 * LlmCostCalculator}）三处同用本记录，避免「存储形状」与「计算输入」漂移。 模型名精确匹配（区分大小写，与 llm_usage.model 快照同域）。
 *
 * @param inputPerMillionCny 每百万输入 token 单价（元）；正数
 * @param outputPerMillionCny 每百万输出 token 单价（元）；正数
 */
public record LlmModelPricing(BigDecimal inputPerMillionCny, BigDecimal outputPerMillionCny) {

    /**
     * 输入单价是否有效（非 null 且 > 0）。
     *
     * @return 有效返回 true
     */
    public boolean hasValidInputPrice() {
        return inputPerMillionCny != null && inputPerMillionCny.signum() > 0;
    }

    /**
     * 输出单价是否有效（非 null 且 > 0）。
     *
     * @return 有效返回 true
     */
    public boolean hasValidOutputPrice() {
        return outputPerMillionCny != null && outputPerMillionCny.signum() > 0;
    }

    /**
     * 两侧单价是否都有效（条目级 fail-closed 判据：任一缺失/非正即整条视为无效）。
     *
     * @return 都有效返回 true
     */
    public boolean isValid() {
        return hasValidInputPrice() && hasValidOutputPrice();
    }
}

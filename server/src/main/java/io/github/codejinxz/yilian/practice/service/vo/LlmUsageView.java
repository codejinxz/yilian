package io.github.codejinxz.yilian.practice.service.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 管理端本月用量视图（API §8.17，预算护栏用量观测）：UTC 自然月口径的已用/预算/百分比/三态 与分场景小计。金额展示层换算（微元 → 元，微元精度如实保留），护栏判定语义见
 * {@code BudgetGuardService}。
 *
 * @param monthUtc 判定月份（UTC 自然月，如 2026-11）
 * @param budgetCny 月度预算（元）；null=未设预算（护栏不生效）
 * @param spentCny 本月已用估算成本（元，微元精度如实保留；只聚合估算值非空的行）
 * @param percent 已用百分比（四舍五入到整数，可超 100）；未设预算时为 null
 * @param status 三态：UNSET=未设预算；OK=&lt;80%；WARN=≥80% 不拦截只提醒；EXCEEDED=≥100% 强制门生效
 * @param byScene 分场景小计（已知三场景缺省为零值行，其他场景按实返回）
 */
@Schema(description = "管理端本月用量视图：UTC 自然月已用/预算/百分比/状态与分场景小计")
public record LlmUsageView(
        @Schema(description = "判定月份（UTC 自然月）", requiredMode = Schema.RequiredMode.REQUIRED)
                String monthUtc,
        @Schema(description = "月度预算（元）；null=未设预算", nullable = true) BigDecimal budgetCny,
        @Schema(description = "本月已用估算成本（元）", requiredMode = Schema.RequiredMode.REQUIRED)
                BigDecimal spentCny,
        @Schema(description = "已用百分比（可超 100）；未设预算为 null", nullable = true) Integer percent,
        @Schema(
                        description = "三态：UNSET=未设预算 | OK | WARN(≥80%) | EXCEEDED(≥100%)",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                String status,
        @Schema(
                        description = "分场景小计：scene → {tokens, costMicroCny, calls}",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                Map<String, SceneUsage> byScene) {

    /** 已知场景（用量视图固定零值兜底的三场景，与记账点 scene 常量同域）。 */
    public static final String SCENE_TUTOR_ASK = "tutor-ask";

    /** 已知场景：模型复核。 */
    public static final String SCENE_REVIEW = "review";

    /** 已知场景：申诉初裁。 */
    public static final String SCENE_APPEAL = "appeal";

    /** status 取值：未设预算（护栏不生效）。 */
    public static final String STATUS_UNSET = "UNSET";

    /** status 取值：正常（&lt;80%）。 */
    public static final String STATUS_OK = "OK";

    /** status 取值：≥80% 提醒（不拦截）。 */
    public static final String STATUS_WARN = "WARN";

    /** status 取值：≥100% 强制门生效。 */
    public static final String STATUS_EXCEEDED = "EXCEEDED";

    /**
     * 单场景小计。
     *
     * @param tokens 累计 token（提示 + 回复）
     * @param costMicroCny 累计估算成本（微元）
     * @param calls 调用次数（成功与失败都计）
     */
    @Schema(description = "单场景小计")
    public record SceneUsage(
            @Schema(description = "累计 token（提示 + 回复）", requiredMode = Schema.RequiredMode.REQUIRED)
                    long tokens,
            @Schema(
                            description = "累计估算成本（微元，1 元=1e6 微元）",
                            requiredMode = Schema.RequiredMode.REQUIRED)
                    long costMicroCny,
            @Schema(description = "调用次数", requiredMode = Schema.RequiredMode.REQUIRED)
                    long calls) {}
}

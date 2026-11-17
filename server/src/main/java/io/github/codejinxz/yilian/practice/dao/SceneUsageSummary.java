package io.github.codejinxz.yilian.practice.dao;

/**
 * llm_usage 按场景聚合投影（预算护栏用量视图用）：单场景一行，数值为当月累计。
 *
 * <p>由 JPA 构造器表达式（GROUP BY scene）填充，SUM 经 COALESCE 兜零——聚合语义在 Dao 层收口，service 层只见已兜底的完整数字，不再处理聚合空值。
 *
 * @param scene 场景标识（tutor-ask / review / appeal 等，与 llm_usage.scene 同域）
 * @param promptTokens 累计提示词 token
 * @param completionTokens 累计回复 token
 * @param costMicroCny 累计估算成本微元（未定价行为 NULL，聚合按 0 兜底）
 * @param calls 调用次数（成功与失败都计：调用即发生）
 */
public record SceneUsageSummary(
        String scene, Long promptTokens, Long completionTokens, Long costMicroCny, Long calls) {

    /** 紧凑构造器：聚合侧兜底缺失数字，service 层无需再判空。 */
    public SceneUsageSummary {
        if (promptTokens == null) {
            promptTokens = 0L;
        }
        if (completionTokens == null) {
            completionTokens = 0L;
        }
        if (costMicroCny == null) {
            costMicroCny = 0L;
        }
        if (calls == null) {
            calls = 0L;
        }
    }

    /**
     * 该场景累计 token（提示 + 回复）。
     *
     * @return 非负累计值
     */
    public long totalTokens() {
        return promptTokens + completionTokens;
    }

    /**
     * 该场景累计估算成本微元。
     *
     * @return 非负累计值
     */
    public long costMicro() {
        return costMicroCny;
    }

    /**
     * 该场景调用次数。
     *
     * @return 非负累计值
     */
    public long callCount() {
        return calls;
    }
}

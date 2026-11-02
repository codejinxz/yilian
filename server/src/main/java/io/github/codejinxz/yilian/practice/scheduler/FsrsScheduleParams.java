package io.github.codejinxz.yilian.practice.scheduler;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 四分支排程公式的参数集：从 ACTIVE 权重版本的 weights JSON 解码。
 *
 * <p>排程公式形状固定（与本地引擎四分支逐值对齐，见 PracticeServiceImpl.scheduleReview）： 失败回炉明天到期；学习中全对 interval=min(7,
 * scheduled+2)、≥5 归档；归档全对只刷新痕迹。 权重只决定公式里的五个常数——本记录是「权重 → 公式参数」的唯一解码点：
 *
 * <ul>
 *   <li>V9 原型种子（对象形 {@code {"schemaVersion":1,"w":[18 维],"note":...}}，V9__review_card_single.sql）：
 *       引擎常量埋在 w[2]=失败稳定度、w[3]=失败难度、w[4]=学习稳定度、w[12]=归档稳定度、w[15]=学习难度 （解码后恰为
 *       2.4/5.3/3.2/40.0/5.1，保证 golden 三实现对拍不变）。
 *   <li>优化器候选（纯数组形，21 维 FSRS-6 顺序，DATA-MODEL §6.8）：按初值语义解码—— 失败分支≈初次 Again（w[0] 初稳定度、w[4]
 *       初难度），学习分支≈初次 Good（w[2] 初稳定度、 初难度公式 w[4]-e^(2·w[5])+1），归档分支≈初次 Easy（w[3] 初稳定度）。
 *       这是工程映射判断而非模型拟合结论：调度公式形状不变，只换参数来源。
 * </ul>
 *
 * @param failStability 失败回炉后的稳定度（原型 2.4）
 * @param failDifficulty 失败回炉后的难度（原型 5.3）
 * @param learningStability 学习中全对的稳定度（原型 3.2）
 * @param learningDifficulty 学习中全对的难度（原型 5.1）
 * @param masteredStability 归档全对刷新的稳定度（原型 40.0）
 */
public record FsrsScheduleParams(
        double failStability,
        double failDifficulty,
        double learningStability,
        double learningDifficulty,
        double masteredStability) {

    /** V9 原型种子的解码结果（等于本地引擎硬编码常量，golden 防线基准）。 */
    public static final FsrsScheduleParams PROTOTYPE =
            new FsrsScheduleParams(2.4, 5.3, 3.2, 5.1, 40.0);

    /**
     * 从 weights JSON 解码排程参数。
     *
     * <p>JSON 解析失败或维度不足一律按内部错误拒绝（50000）：权重行由迁移种子与优化器写入， 出现脏数据说明上游契约被破坏，不能静默回退到原型值掩盖问题。
     *
     * @param weightsJson fsrs_parameter_versions.weights 原文
     * @param objectMapper JSON 解析器（与判分落库共用）
     * @return 排程参数
     */
    public static FsrsScheduleParams fromWeightsJson(
            String weightsJson, ObjectMapper objectMapper) {
        JsonNode root;
        try {
            root = objectMapper.readTree(weightsJson);
        } catch (RuntimeException exception) {
            throw new BusinessException(ApiCode.INTERNAL_ERROR, "FSRS 权重 JSON 不合法");
        }
        if (root == null) {
            throw new BusinessException(ApiCode.INTERNAL_ERROR, "FSRS 权重 JSON 不合法");
        }
        if (root.isArray()) {
            return fromFsrs6Array(root);
        }
        if (root.has("w") && root.get("w").isArray()) {
            return fromPrototypeObject(root.get("w"));
        }
        throw new BusinessException(ApiCode.INTERNAL_ERROR, "FSRS 权重形状不合法");
    }

    /**
     * 解码 V9 原型种子（18 维对象形）：引擎常量埋点见类注释。
     *
     * @param weights "w" 数组节点
     * @return 排程参数
     */
    private static FsrsScheduleParams fromPrototypeObject(JsonNode weights) {
        if (weights.size() < 16) {
            throw new BusinessException(ApiCode.INTERNAL_ERROR, "原型权重维度不足");
        }
        return new FsrsScheduleParams(
                weights.get(2).asDouble(),
                weights.get(3).asDouble(),
                weights.get(4).asDouble(),
                weights.get(15).asDouble(),
                weights.get(12).asDouble());
    }

    /**
     * 解码优化器候选（21 维 FSRS-6 纯数组，DATA-MODEL §6.8）。
     *
     * @param weights 数组节点
     * @return 排程参数
     */
    private static FsrsScheduleParams fromFsrs6Array(JsonNode weights) {
        if (weights.size() < 21) {
            throw new BusinessException(ApiCode.INTERNAL_ERROR, "FSRS-6 权重必须为 21 维");
        }
        double failDifficulty = weights.get(4).asDouble();
        double learningDifficulty =
                weights.get(4).asDouble() - Math.exp(2 * weights.get(5).asDouble()) + 1;
        return new FsrsScheduleParams(
                weights.get(0).asDouble(),
                failDifficulty,
                weights.get(2).asDouble(),
                learningDifficulty,
                weights.get(3).asDouble());
    }
}

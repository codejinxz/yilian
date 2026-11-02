package io.github.codejinxz.yilian.practice.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/** 权重 JSON → 四分支排程参数的解码测试 [AC-5]：原型种子逐值一致是 golden 防线的前提。 */
class FsrsScheduleParamsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** [AC-5] V9 原型种子（18 维对象形）解码后与本地引擎常量逐值相等。 */
    @Test
    @DisplayName("[AC-5] V9 原型种子解码 = 本地引擎常量")
    void ac5_prototypeSeedDecodesToEngineConstants() {
        // V9__review_card_single.sql 的种子 weights 原文。
        String seedJson =
                "{\"schemaVersion\": 1, \"w\": [0.4, 1.2, 2.4, 5.3, 3.2, 0.8, 0.6, 2.0, 1.5,"
                        + " 0.3, 1.0, 2.0, 40.0, 0.6, 3.0, 5.1, 0.9, 0.4],"
                        + " \"note\": \"prototype placeholder; optimizer lands in M2.1\"}";
        assertThat(FsrsScheduleParams.fromWeightsJson(seedJson, objectMapper))
                .isEqualTo(FsrsScheduleParams.PROTOTYPE);
        assertThat(FsrsScheduleParams.PROTOTYPE.failStability()).isEqualTo(2.4);
        assertThat(FsrsScheduleParams.PROTOTYPE.failDifficulty()).isEqualTo(5.3);
        assertThat(FsrsScheduleParams.PROTOTYPE.learningStability()).isEqualTo(3.2);
        assertThat(FsrsScheduleParams.PROTOTYPE.learningDifficulty()).isEqualTo(5.1);
        assertThat(FsrsScheduleParams.PROTOTYPE.masteredStability()).isEqualTo(40.0);
    }

    /** [AC-5] 优化器候选（21 维 FSRS-6 纯数组）按初值语义解码。 */
    @Test
    @DisplayName("[AC-5] FSRS-6 21 维数组按初值语义解码")
    void ac5_fsrs6ArrayDecodesByInitialValueSemantics() {
        double[] defaults = FsrsOptimizerDefaults.FSRS6;
        String arrayJson = toJson(defaults);
        FsrsScheduleParams params = FsrsScheduleParams.fromWeightsJson(arrayJson, objectMapper);
        // 失败分支≈初次 Again：w[0] 初稳定度、w[4] 初难度。
        assertThat(params.failStability()).isEqualTo(defaults[0]);
        assertThat(params.failDifficulty()).isEqualTo(defaults[4]);
        // 学习分支≈初次 Good：w[2] 初稳定度、初难度公式 w[4]-e^(2·w[5])+1。
        assertThat(params.learningStability()).isEqualTo(defaults[2]);
        assertThat(params.learningDifficulty())
                .isEqualTo(defaults[4] - Math.exp(2 * defaults[5]) + 1);
        // 归档分支≈初次 Easy：w[3] 初稳定度。
        assertThat(params.masteredStability()).isEqualTo(defaults[3]);
    }

    /** 脏数据按内部错误拒绝：不静默回退原型值掩盖契约破坏。 */
    @Test
    @DisplayName("[AC-5] 非法权重 JSON 按 50000 拒绝")
    void ac5_malformedWeightsRejectedWith50000() {
        assertThatThrownBy(() -> FsrsScheduleParams.fromWeightsJson("{not-json", objectMapper))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode()).isEqualTo(ApiCode.INTERNAL_ERROR));
        assertThatThrownBy(() -> FsrsScheduleParams.fromWeightsJson("[]", objectMapper))
                .isInstanceOf(BusinessException.class);
        // 维度不足的数组（<21）与缺 "w" 键的对象同样拒绝。
        assertThatThrownBy(
                        () ->
                                FsrsScheduleParams.fromWeightsJson(
                                        toJson(new double[] {0.1, 0.2, 0.3}), objectMapper))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () -> FsrsScheduleParams.fromWeightsJson("{\"note\": \"x\"}", objectMapper))
                .isInstanceOf(BusinessException.class);
    }

    /** [D-2 回归] weightsArray 双形态解码：数组形原样返回、对象形取 "w" 数组、其他形状 50000。 */
    @Test
    @DisplayName("[D-2] weightsArray 双形态解码")
    void d2_weightsArrayDecodesBothShapes() {
        // 数组形（优化器候选）：原样返回 21 维。
        double[] array =
                FsrsScheduleParams.weightsArray(toJson(FsrsOptimizerDefaults.FSRS6), objectMapper);
        assertThat(array).containsExactly(FsrsOptimizerDefaults.FSRS6);
        // 对象形（V9 种子）：取 "w" 数组原值（18 维，不伪造补齐到 21）——管理端版本列表曾因此 500。
        String seedJson =
                "{\"schemaVersion\": 1, \"w\": [0.4, 1.2, 2.4, 5.3, 3.2, 0.8, 0.6, 2.0, 1.5,"
                        + " 0.3, 1.0, 2.0, 40.0, 0.6, 3.0, 5.1, 0.9, 0.4],"
                        + " \"note\": \"prototype placeholder; optimizer lands in M2.1\"}";
        assertThat(FsrsScheduleParams.weightsArray(seedJson, objectMapper))
                .containsExactly(
                        0.4, 1.2, 2.4, 5.3, 3.2, 0.8, 0.6, 2.0, 1.5, 0.3, 1.0, 2.0, 40.0, 0.6, 3.0,
                        5.1, 0.9, 0.4);
        // 非数组/无 "w" 键的对象：50000。
        assertThatThrownBy(() -> FsrsScheduleParams.weightsArray("{\"note\": \"x\"}", objectMapper))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode()).isEqualTo(ApiCode.INTERNAL_ERROR));
    }

    /**
     * 序列化为 JSON 数组。
     *
     * @param weights 权重
     * @return JSON 字符串
     */
    private String toJson(double[] weights) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < weights.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(weights[i]);
        }
        return json.append(']').toString();
    }

    /** FSRS-6 默认权重（与 fsrs 库 Scheduler.DEFAULT_PARAMETERS 一致，测试用常量）。 */
    static final class FsrsOptimizerDefaults {
        static final double[] FSRS6 = {
            0.2172, 1.1771, 3.2602, 16.1507, 7.0114, 0.57, 2.0966, 0.0069, 1.5261, 0.112, 1.0178,
            1.849, 0.1133, 0.3127, 2.2934, 0.2191, 3.0004, 0.7536, 0.3332, 0.1437, 0.2
        };

        private FsrsOptimizerDefaults() {}
    }
}

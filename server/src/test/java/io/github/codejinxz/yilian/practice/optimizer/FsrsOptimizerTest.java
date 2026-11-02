package io.github.codejinxz.yilian.practice.optimizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.github.openspacedrepetition.Card;
import io.github.openspacedrepetition.Rating;
import io.github.openspacedrepetition.Scheduler;
import io.github.openspacedrepetition.State;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * FSRS 优化器单元测试 [AC-4]：合成数据（已知真值权重生成）上的 SGD 收敛证据 + 与 fsrs 库 FSRS-6 公式的对拍。
 *
 * <p>收敛阈值刻意宽松：单人产品的数据量下，梯度下降的目标是「落在真值邻域 + 损失显著下降」， 证明训练真实发生而非随机，不追求参数级复现。
 */
class FsrsOptimizerTest {

    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");

    /**
     * 真值权重：FSRS-6 默认的确定性扰动。可辨识维度（初值/衰减/难度更新）大幅偏移， 弱可辨识维度（均值回归锚点 w7、短项系数
     * w13/w18/w19）保持默认——单人数据量下它们不可恢复， 收敛断言只针对可辨识参数（平均值口径已把弱维度视为零误差）。
     */
    private static final double[] TRUTH = {
        0.9, 1.6, 1.8, 9.0, 5.2, 0.45, 3.0, 0.0069, 0.9, 0.2, 1.4, 1.3, 0.25, 0.3127, 1.7, 0.05,
        2.2, 0.9, 0.3332, 0.1437, 0.45
    };

    /** [AC-4] 可召回模型对拍：库的 getCardRetrievability 与 FSRS-6 遗忘曲线闭式逐值一致。 */
    @Test
    @DisplayName("[AC-4] 可召回模型与 FSRS-6 闭式对拍")
    void ac4_retrievabilityMatchesFsrs6ClosedForm() {
        Scheduler scheduler = scheduler(FsrsOptimizer.FSRS6_DEFAULT_WEIGHTS);
        // 已知状态的卡：S=5.0，上次复习 3 天前。
        Card card =
                Card.builder()
                        .cardId(0)
                        .state(State.REVIEW)
                        .stability(5.0)
                        .difficulty(5.0)
                        .lastReview(T0)
                        .build();
        double decay = scheduler.getDECAY();
        double factor = scheduler.getFACTOR();
        for (int days : new int[] {0, 1, 3, 7, 30}) {
            double libraryValue =
                    scheduler.getCardRetrievability(card, T0.plus(days, ChronoUnit.DAYS));
            double closedForm = Math.pow(1 + factor * days / 5.0, decay);
            assertThat(libraryValue).isCloseTo(closedForm, within(1e-12));
        }
        // DECAY=-w20、FACTOR=0.9^(1/DECAY)-1 的派生关系也与库一致。
        assertThat(decay).isEqualTo(-FsrsOptimizer.FSRS6_DEFAULT_WEIGHTS[20], within(1e-12));
        assertThat(factor).isEqualTo(Math.pow(0.9, 1.0 / decay) - 1, within(1e-12));
    }

    /** [AC-4] 训练读数对拍：单卡两次复习的 BCE 可用 FSRS-6 初值+遗忘曲线手算逐值复现。 */
    @Test
    @DisplayName("[AC-4] BCE 读数可由 FSRS-6 公式手算复现")
    void ac4_bceReproducibleByHandComputation() {
        double[] weights = FsrsOptimizer.FSRS6_DEFAULT_WEIGHTS.clone();
        // 首评 GOOD(3) 落初稳定度 w[2]；3 天后再评 AGAIN(1)：p=(1+FACTOR·3/S1)^DECAY，y=0 → BCE=-ln(1-p)。
        double s1 = weights[2];
        double decay = -weights[20];
        double factor = Math.pow(0.9, 1.0 / decay) - 1;
        double p = Math.pow(1 + factor * 3 / s1, decay);
        double handBce = -Math.log(1 - p);

        List<FsrsOptimizer.Sample> samples =
                List.of(
                        new FsrsOptimizer.Sample(1L, T0, 3),
                        new FsrsOptimizer.Sample(1L, T0.plus(3, ChronoUnit.DAYS), 1));
        double optimizerBce = FsrsOptimizer.bceLoss(weights, FsrsOptimizer.groupByCard(samples));
        assertThat(optimizerBce).isCloseTo(handBce, within(1e-9));
    }

    /** [AC-4] SGD 收敛：真值权重生成的合成日志上，损失单调不升、显著下降、参数落真值邻域。 */
    @Test
    @DisplayName("[AC-4] 合成数据上 SGD 收敛到真值邻域")
    void ac4_sgdConvergesToTruthNeighborhood() {
        List<FsrsOptimizer.Sample> samples = syntheticLogs(TRUTH, 150, 42L);
        assertThat(samples.size()).isGreaterThanOrEqualTo(FsrsOptimizer.MIN_SAMPLES);

        FsrsOptimizer.Result result = FsrsOptimizer.optimize(samples);

        // 损失显著下降：初始（FSRS-6 默认权重）vs 训练后。
        assertThat(result.finalLoss()).isLessThan(result.initialLoss());
        assertThat(result.initialLoss() - result.finalLoss()).isGreaterThan(0.01);
        // 逐迭代单调不升（回溯半步的保证）。
        for (int i = 1; i < result.lossTrace().length; i++) {
            assertThat(result.lossTrace()[i])
                    .as("lossTrace[%d]", i)
                    .isLessThanOrEqualTo(result.lossTrace()[i - 1] + 1e-12);
        }
        // 参数落真值邻域（宽松阈值）：21 维平均误差与两个可辨识维度的误差都有界。
        double meanAbsError = 0;
        for (int i = 0; i < 21; i++) {
            meanAbsError += Math.abs(result.weights()[i] - TRUTH[i]);
        }
        meanAbsError /= 21;
        assertThat(meanAbsError).as("21 维平均绝对误差").isLessThan(1.5);
        assertThat(result.weights()[0]).as("w0 初稳定度").isCloseTo(TRUTH[0], within(1.5));
        // DECAY（w20）是遗忘曲线的最敏感维度：诊断实测误差 ~0.02，阈值留 7 倍余量。
        assertThat(result.weights()[20]).as("w20 DECAY").isCloseTo(TRUTH[20], within(0.15));
        // 训练后权重仍在可行域内（钳制边界）。
        for (int i = 0; i <= 3; i++) {
            assertThat(result.weights()[i]).isGreaterThanOrEqualTo(0.001);
        }
        assertThat(result.weights()[20]).isBetween(0.01, 8.0);
        // 摘要自洽。
        assertThat(result.sampleCount()).isEqualTo(samples.size());
        assertThat(result.lossEvents()).isEqualTo(samples.size() - 150);
        assertThat(result.rmse()).isBetween(0.0, 1.0);
    }

    /** 训练样本下限在优化器核心同样守卫（service 层映射为 40000）。 */
    @Test
    @DisplayName("[AC-4] 样本不足 50 拒绝训练")
    void ac4_rejectsInsufficientSamples() {
        List<FsrsOptimizer.Sample> samples = syntheticLogs(TRUTH, 6, 7L);
        assertThat(samples.size()).isLessThan(FsrsOptimizer.MIN_SAMPLES);
        assertThatThrownBy(() -> FsrsOptimizer.optimize(samples))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("50");
    }

    /**
     * 用真值权重模拟真实学习过程生成合成日志：先评一次（固定模式），之后按真值可提取率采样回忆结果。
     *
     * <p>固定种子保证测试可复现；回忆成功记 GOOD/EASY（偶数/奇数），失败记 AGAIN—— 与 deriveRating 的四档语义同构（训练只看 rating，不看分数）。
     *
     * @param truth 真值权重
     * @param cardCount 卡数
     * @param seed 随机种子
     * @return 合成样本
     */
    private static List<FsrsOptimizer.Sample> syntheticLogs(
            double[] truth, int cardCount, long seed) {
        Scheduler truthScheduler = scheduler(truth);
        Random random = new Random(seed);
        int[] firstRatings = {3, 3, 2, 4, 3, 1};
        List<FsrsOptimizer.Sample> samples = new ArrayList<>();
        for (int cardIndex = 0; cardIndex < cardCount; cardIndex++) {
            // 调度器不改动入参卡（内部拷贝），直接推进同一张卡即可让记忆状态随真值演化。
            Card card = Card.builder().cardId(0).build();
            long day = 0;
            for (int reviewIndex = 0; reviewIndex < 8; reviewIndex++) {
                // 间隔随复习次数展开（1–40 天）：长间隔让 w8–w11/w20 的可辨识度上来。
                day += reviewIndex == 0 ? 0 : 1 + random.nextInt(Math.min(40, reviewIndex * 5));
                Instant at = T0.plus(day, ChronoUnit.DAYS);
                int rating;
                if (reviewIndex == 0) {
                    rating = firstRatings[cardIndex % firstRatings.length];
                } else {
                    double p = truthScheduler.getCardRetrievability(card, at);
                    if (random.nextDouble() < p) {
                        rating = random.nextBoolean() ? 3 : 4;
                    } else {
                        rating = 1;
                    }
                }
                samples.add(new FsrsOptimizer.Sample(cardIndex + 1L, at, rating));
                card = truthScheduler.reviewCard(card, Rating.values()[rating - 1], at).card();
            }
        }
        return samples;
    }

    /**
     * 构造模拟用调度器（与优化器同口径：空学习步、关模糊）。
     *
     * @param weights 权重
     * @return 调度器
     */
    private static Scheduler scheduler(double[] weights) {
        return Scheduler.builder()
                .parameters(weights.clone())
                .learningSteps(new Duration[0])
                .relearningSteps(new Duration[0])
                .enableFuzzing(false)
                .build();
    }
}

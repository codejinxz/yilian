package io.github.codejinxz.yilian.practice.optimizer;

import io.github.openspacedrepetition.Card;
import io.github.openspacedrepetition.CardAndReviewLog;
import io.github.openspacedrepetition.Rating;
import io.github.openspacedrepetition.Scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FSRS-6 参数优化器（M2.1，纯函数核心，便于测试）：读某用户 review_logs 的评分序列， 用 BCE 损失对全部 21 维权重做全批梯度下降（数值梯度 +
 * 回溯半步），产出候选权重。
 *
 * <p>数学要点（与 py-fsrs/fsrs-rs 优化器同构，量级收紧为单人产品）：
 *
 * <ul>
 *   <li>记忆状态模拟：每张卡（review_id 分组）按 reviewAt 重放——首评用 FSRS-6 初值公式落 S/D， 之后每次复习先用库的可提取率公式预测 p，再让库推进
 *       S/D（库即对拍基准，见 Scheduler）。 模拟用空学习步/关模糊，保证 S/D 演化只由权重决定。
 *   <li>可召回模型：R(t) = (1 + FACTOR·t/S)^(-w20)，FACTOR=0.9^(1/DECAY)-1，DECAY=-w20； 与
 *       Scheduler.getCardRetrievability 同式（本优化器直接调库取值）。
 *   <li>标签：rating=AGAIN(1) 记遗忘 y=0，HARD/GOOD/EASY 记记住 y=1（FSRS 优化器惯例）； 每张卡的首评无「复习前状态」，不进损失。
 *   <li>损失：BCE = -mean(y·ln p + (1-y)·ln(1-p))，p 截断到 [1e-6, 1-1e-6]。
 *   <li>下降：全批中心差分梯度（eps=1e-4）+ Adam 自适应步长（确定性更新，无随机成分）， 每步回溯（损失上升则减半步长重试），损失逐迭代单调不升；全批 + 回溯 =
 *       可复现、无随机种子。 Adam 的必要性：BCE 在默认权重附近梯度量级 1e-2，普通 GD 固定学习率会爬行（实测 200 迭代 移动不足 0.1），Adam 按梯度 RMS
 *       归一后每维步长≈lr，可辨识维度数百迭代内落邻域。
 * </ul>
 *
 * <p>「没有真实数据前不生成候选权重」：样本不足由调用方（service 层）拒绝，本类不做 IO。
 */
public final class FsrsOptimizer {

    /** 训练样本下限（review_logs 行数）；低于该值不训练，避免过拟合与「伪优化」。 */
    public static final int MIN_SAMPLES = 50;

    /** 固定迭代数：单人产品数据量级（百~千条）下的收敛/耗时折中，摘要落库。 */
    public static final int ITERATIONS = 300;

    /** 固定 Adam 学习率：回溯减半兜底后，该值在合成数据上稳定收敛（见 FsrsOptimizerTest）。 */
    public static final double LEARNING_RATE = 0.05;

    /** Adam 一阶矩衰减。 */
    private static final double ADAM_BETA1 = 0.9;

    /** Adam 二阶矩衰减。 */
    private static final double ADAM_BETA2 = 0.999;

    /** Adam 数值稳定项。 */
    private static final double ADAM_EPSILON = 1e-8;

    /** FSRS-6 官方默认权重（java-fsrs 1.0.0 Scheduler.DEFAULT_PARAMETERS，21 维）。 */
    public static final double[] FSRS6_DEFAULT_WEIGHTS = {
        0.2172, 1.1771, 3.2602, 16.1507, 7.0114, 0.57, 2.0966, 0.0069, 1.5261, 0.112, 1.0178, 1.849,
        0.1133, 0.3127, 2.2934, 0.2191, 3.0004, 0.7536, 0.3332, 0.1437, 0.2
    };

    /** 数值梯度步长。 */
    private static final double EPSILON = 1e-4;

    /** 概率截断下界。 */
    private static final double P_MIN = 1e-6;

    /** 概率截断上界。 */
    private static final double P_MAX = 1 - 1e-6;

    /** 优化器标识：落 metrics.optimizer，说明这是 M2.1 的 Java 全批 SGD。 */
    public static final String OPTIMIZER_ID = "java-sgd-m2.1";

    /** 优化器版本：迭代/学习率等超参变化时递增。 */
    public static final String OPTIMIZER_VERSION = "1.0";

    /** 单条训练样本：review_logs 的一次排程事件。 */
    public record Sample(long reviewId, Instant reviewAt, int rating) {}

    /** 优化结果：候选权重与训练摘要（落 fsrs_parameter_versions.weights/metrics）。 */
    public record Result(
            double[] weights,
            int sampleCount,
            int lossEvents,
            double initialLoss,
            double finalLoss,
            double rmse,
            int iterations,
            double[] lossTrace) {}

    /** 工具类禁实例化。 */
    private FsrsOptimizer() {}

    /**
     * 以默认超参优化：起始权重 = FSRS-6 官方默认。
     *
     * @param samples 用户的排程日志样本（reviewId 分组、reviewAt 升序由本方法排序）
     * @return 优化结果
     */
    public static Result optimize(List<Sample> samples) {
        return optimize(samples, FSRS6_DEFAULT_WEIGHTS.clone(), ITERATIONS, LEARNING_RATE);
    }

    /**
     * 全批梯度下降主入口。
     *
     * @param samples 排程日志样本
     * @param initialWeights 起始权重（21 维，调用方负责克隆，本方法不修改入参）
     * @param iterations 固定迭代数
     * @param learningRate 初始学习率（回溯半步兜底）
     * @return 优化结果
     * @throws IllegalArgumentException 样本不足或权重维度不为 21
     */
    public static Result optimize(
            List<Sample> samples, double[] initialWeights, int iterations, double learningRate) {
        if (samples.size() < MIN_SAMPLES) {
            throw new IllegalArgumentException("训练样本不足 " + MIN_SAMPLES + " 条: " + samples.size());
        }
        if (initialWeights.length != 21) {
            throw new IllegalArgumentException("FSRS-6 权重必须为 21 维");
        }
        Map<Long, List<Sample>> byCard = groupByCard(samples);
        double[] weights = initialWeights.clone();
        double loss = bceLoss(weights, byCard);
        double initialLoss = loss;
        double lastLoss = loss;
        // Adam 状态：全批梯度下无随机性，一/二阶矩只依赖确定性梯度序列。
        double[] firstMoment = new double[21];
        double[] secondMoment = new double[21];
        double[] lossTrace = new double[iterations];
        for (int iteration = 0; iteration < iterations; iteration++) {
            double[] gradient = numericalGradient(weights, byCard);
            int timestep = iteration + 1;
            for (int i = 0; i < 21; i++) {
                firstMoment[i] = ADAM_BETA1 * firstMoment[i] + (1 - ADAM_BETA1) * gradient[i];
                secondMoment[i] =
                        ADAM_BETA2 * secondMoment[i] + (1 - ADAM_BETA2) * gradient[i] * gradient[i];
            }
            double step = learningRate;
            // 回溯减半：全批下降的非随机保险，保证损失逐迭代不升（AC-4「loss 单调下降」的实现口径）。
            for (int backtrack = 0; backtrack < 8; backtrack++) {
                double[] candidate =
                        applyAdamStep(weights, firstMoment, secondMoment, step, timestep);
                double candidateLoss = bceLoss(candidate, byCard);
                if (candidateLoss <= lastLoss) {
                    weights = candidate;
                    lastLoss = candidateLoss;
                    break;
                }
                step /= 2;
            }
            lossTrace[iteration] = lastLoss;
        }
        Loss evaluation = evaluate(weights, byCard);
        return new Result(
                weights,
                samples.size(),
                evaluation.events(),
                initialLoss,
                evaluation.loss(),
                evaluation.rmse(),
                iterations,
                lossTrace);
    }

    /**
     * BCE 损失（模拟重放 + 库公式预测）。
     *
     * @param weights 候选权重
     * @param byCard 按卡分组的样本
     * @return 平均 BCE
     */
    public static double bceLoss(double[] weights, Map<Long, List<Sample>> byCard) {
        return evaluate(weights, byCard).loss();
    }

    /**
     * 按卡分组并把每张卡的样本按 reviewAt 升序稳定排序。
     *
     * @param samples 原始样本
     * @return 分组结果（LinkedHashMap 保持插入序，保证可复现）
     */
    public static Map<Long, List<Sample>> groupByCard(List<Sample> samples) {
        Map<Long, List<Sample>> byCard = new LinkedHashMap<>();
        for (Sample sample : samples) {
            byCard.computeIfAbsent(sample.reviewId(), key -> new ArrayList<>()).add(sample);
        }
        for (List<Sample> card : byCard.values()) {
            // 稳定排序：同一时刻的日志保持插入序（id 序），回放确定性不依赖输入顺序。
            card.sort(java.util.Comparator.comparing(Sample::reviewAt));
        }
        return byCard;
    }

    /**
     * 中心差分数值梯度（21 维）：解析梯度需要推导 FSRS-6 公式链，数值差分足够且不会与库漂移。
     *
     * @param weights 当前权重
     * @param byCard 分组样本
     * @return 梯度向量
     */
    private static double[] numericalGradient(double[] weights, Map<Long, List<Sample>> byCard) {
        double[] gradient = new double[weights.length];
        for (int i = 0; i < weights.length; i++) {
            double original = weights[i];
            weights[i] = original + EPSILON;
            double plus = evaluate(weights, byCard).loss();
            weights[i] = original - EPSILON;
            double minus = evaluate(weights, byCard).loss();
            weights[i] = original;
            gradient[i] = (plus - minus) / (2 * EPSILON);
        }
        return gradient;
    }

    /**
     * 应用一步 Adam 更新并做边界钳制（FSRS-6 权重的可行域：稳定度初值非负、DECAY=-w20 保持负）。
     *
     * @param weights 当前权重
     * @param firstMoment 一阶矩
     * @param secondMoment 二阶矩
     * @param step 学习率（回溯时可减半）
     * @param timestep 迭代序（1 起，用于偏差修正）
     * @return 新权重
     */
    private static double[] applyAdamStep(
            double[] weights,
            double[] firstMoment,
            double[] secondMoment,
            double step,
            int timestep) {
        double biasCorrection1 = 1 - Math.pow(ADAM_BETA1, timestep);
        double biasCorrection2 = 1 - Math.pow(ADAM_BETA2, timestep);
        double[] next = new double[weights.length];
        for (int i = 0; i < weights.length; i++) {
            double mHat = firstMoment[i] / biasCorrection1;
            double vHat = secondMoment[i] / biasCorrection2;
            double value = weights[i] - step * mHat / (Math.sqrt(vHat) + ADAM_EPSILON);
            if (!Double.isFinite(value)) {
                // 数值异常时保留原值：一步坏梯度不应毁掉整轮训练。
                value = weights[i];
            }
            next[i] = clampWeight(value, i);
        }
        return next;
    }

    /**
     * 权重边界钳制。
     *
     * @param value 原值
     * @param index 维度
     * @return 钳制后的值
     */
    private static double clampWeight(double value, int index) {
        if (index <= 3) {
            // 初始稳定度：必须为正（STABILITY_MIN 与库一致）。
            return Math.min(Math.max(value, Scheduler.STABILITY_MIN), 100.0);
        }
        if (index == 20) {
            // DECAY = -w20 必须为负：w20 ∈ [0.01, 8]（FSRS 文献可行域）。
            return Math.min(Math.max(value, 0.01), 8.0);
        }
        return Math.min(Math.max(value, -10.0), 100.0);
    }

    /**
     * 重放全部卡并计算损失/事件数/RMSE。
     *
     * @param weights 候选权重
     * @param byCard 分组样本
     * @return 评估结果
     */
    private static Loss evaluate(double[] weights, Map<Long, List<Sample>> byCard) {
        Scheduler scheduler = simulationScheduler(weights);
        double bceSum = 0;
        double squaredErrorSum = 0;
        int events = 0;
        for (List<Sample> cardSamples : byCard.values()) {
            Card card = new Card(cardTemplate());
            boolean first = true;
            for (Sample sample : cardSamples) {
                if (!first) {
                    double predicted =
                            clamp(scheduler.getCardRetrievability(card, sample.reviewAt()));
                    double actual = sample.rating() == Rating.AGAIN.getValue() ? 0 : 1;
                    bceSum -= actual * Math.log(predicted) + (1 - actual) * Math.log(1 - predicted);
                    squaredErrorSum += (predicted - actual) * (predicted - actual);
                    events++;
                }
                CardAndReviewLog advanced =
                        scheduler.reviewCard(card, ratingOf(sample.rating()), sample.reviewAt());
                card = advanced.card();
                first = false;
            }
        }
        if (events == 0) {
            return new Loss(0, 0, 0);
        }
        return new Loss(bceSum / events, events, Math.sqrt(squaredErrorSum / events));
    }

    /**
     * 模拟用调度器：空学习步（首评即毕业）、关模糊（间隔不影响 S/D 演化，此处只为确定性）。
     *
     * @param weights 候选权重
     * @return 调度器
     */
    private static Scheduler simulationScheduler(double[] weights) {
        return Scheduler.builder()
                .parameters(weights.clone())
                .learningSteps(new Duration[0])
                .relearningSteps(new Duration[0])
                .enableFuzzing(false)
                .build();
    }

    /**
     * 卡模板：cardId 固定 0，绕开 Card.builder() 的自动 id（其 build() 会 sleep 1ms， 重放中每次建卡会放大成秒级开销）；每次重放用拷贝构造复位。
     *
     * @return 新卡模板
     */
    private static Card cardTemplate() {
        return Card.builder().cardId(0).build();
    }

    /**
     * 评分值 → 库枚举。
     *
     * @param rating 1–4
     * @return Rating
     */
    private static Rating ratingOf(int rating) {
        return switch (rating) {
            case 1 -> Rating.AGAIN;
            case 2 -> Rating.HARD;
            case 3 -> Rating.GOOD;
            default -> Rating.EASY;
        };
    }

    /**
     * 概率截断。
     *
     * @param p 原始可提取率
     * @return 截断后的概率
     */
    private static double clamp(double p) {
        return Math.min(Math.max(p, P_MIN), P_MAX);
    }

    /** 评估内部结果。 */
    private record Loss(double loss, int events, double rmse) {}
}

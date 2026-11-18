package io.github.codejinxz.yilian.practice.llm;

import io.github.codejinxz.yilian.practice.dao.LlmUsageDao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * 月度预算护栏判定（PRD §17）：把本月已用估算成本对比管理端配置的月度预算，产出三态 {@code OK / WARN(≥80%) / EXCEEDED(≥100%)}。月度口径 =
 * <strong>UTC 自然月</strong>（AGENTS §3 全 UTC 存储：{@code created_at >= UTC 月初} 起算，跨月自然重置——上月落库行不再计入）。
 *
 * <p>判定走直查不缓存：三个调用点（答疑/复核/申诉初裁）均为用户低频操作，查询开销可忽略； L2 缓存属分层预留不接线。软护栏语义：pre-call 判定不持锁，并发同窗多笔调用 可微量越过
 * 100%（PRD 未要求硬会计，不做分布式锁）。未设预算键=护栏不生效（现行为）： 直通且不触用量表。
 *
 * <p>WARN 不拦截只留痕：≥80% 时记结构化 WARN 日志 + 管理端用量视图状态可见（无邮件/推送渠道， PRD「提醒」的如实落地口径）。
 */
@Component
public class BudgetGuardService {

    private static final Logger log = LoggerFactory.getLogger(BudgetGuardService.class);

    /** WARN 阈值：用量达预算 80%（PRD §17「到 80% 提醒管理员」）。 */
    static final BigDecimal WARN_RATIO = new BigDecimal("0.8");

    private final LlmBudgetSettings settings;
    private final LlmUsageDao llmUsageDao;

    /**
     * 注入依赖。
     *
     * @param settings 预算与定价配置存取（app_settings 键 llm.budget）
     * @param llmUsageDao 用量账本 Dao（月度 SUM 直查）
     */
    public BudgetGuardService(LlmBudgetSettings settings, LlmUsageDao llmUsageDao) {
        this.settings = settings;
        this.llmUsageDao = llmUsageDao;
    }

    /** 预算三态。 */
    public enum Status {
        /** 正常（或未设预算=护栏不生效的直通态）。 */
        OK,
        /** 已达 80% 及以上：不拦截，留痕提醒（日志 + 用量视图状态）。 */
        WARN,
        /** 已达 100%：强制门拦截新调用（答疑 40306；复核/初裁走各自降级）。 */
        EXCEEDED
    }

    /**
     * 一次判定快照。
     *
     * @param monthUtc 判定月份（UTC 自然月）
     * @param budgetCny 月度预算（元）；null=未设预算（护栏不生效）
     * @param spentMicroCny 本月已用估算成本（微元，只聚合估算值非空的行）
     * @param percent 已用百分比（四舍五入到整数，可超 100）；未设预算时为 null
     * @param status 三态判定
     */
    public record Snapshot(
            YearMonth monthUtc,
            BigDecimal budgetCny,
            long spentMicroCny,
            Integer percent,
            Status status) {

        /**
         * 是否已设有效预算。
         *
         * @return 已设预算返回 true
         */
        public boolean hasBudget() {
            return budgetCny != null;
        }
    }

    /**
     * 强制门判定（答疑/复核/初裁三调用点共用）：未设预算直通且不触用量表；已设预算实算三态。
     *
     * @return 三态判定
     */
    public Status gate() {
        LlmBudgetSettings.Config config = settings.read();
        if (!config.hasBudget()) {
            return Status.OK;
        }
        return snapshot(config).status();
    }

    /**
     * 完整快照（管理端用量视图共用）：始终实算本月已用（视图无论是否设预算都要展示已用金额）。 未设预算时 budgetCny/percent 为
     * null、status=OK（直通语义）；已达阈值时留痕 WARN 日志。
     *
     * @return 判定快照
     */
    public Snapshot snapshot() {
        return snapshot(settings.read());
    }

    /**
     * 按给定配置实算快照（gate 与 snapshot 的共用内核）。
     *
     * @param config 预算配置
     * @return 判定快照
     */
    private Snapshot snapshot(LlmBudgetSettings.Config config) {
        YearMonth month = YearMonth.now(ZoneOffset.UTC);
        Instant monthStart = monthStartUtc(month);
        long spentMicroCny = llmUsageDao.sumEstimatedCostSince(monthStart);
        if (!config.hasBudget()) {
            return new Snapshot(month, null, spentMicroCny, null, Status.OK);
        }
        long budgetMicroCny = budgetMicroCny(config.monthlyBudgetCny());
        Status status = evaluate(spentMicroCny, budgetMicroCny);
        Integer percent = null;
        if (budgetMicroCny > 0) {
            percent =
                    BigDecimal.valueOf(spentMicroCny)
                            .multiply(BigDecimal.valueOf(100))
                            .divide(BigDecimal.valueOf(budgetMicroCny), 0, RoundingMode.HALF_UP)
                            .intValue();
        }
        if (status != Status.OK) {
            log.warn(
                    "LLM 月度预算用量达阈值：status={}, monthUtc={}, percent={}, budgetCny={},"
                            + " spentMicroCny={}",
                    status,
                    month,
                    percent,
                    config.monthlyBudgetCny(),
                    spentMicroCny);
        }
        return new Snapshot(month, config.monthlyBudgetCny(), spentMicroCny, percent, status);
    }

    /**
     * 三态判定（整数域精确比较，不做浮点除法）：EXCEEDED=已用≥100%；WARN=已用≥80%；否则 OK。
     *
     * @param spentMicroCny 已用微元
     * @param budgetMicroCny 预算微元（>0）
     * @return 三态判定
     */
    private static Status evaluate(long spentMicroCny, long budgetMicroCny) {
        BigDecimal spent = BigDecimal.valueOf(spentMicroCny);
        if (spent.compareTo(BigDecimal.valueOf(budgetMicroCny)) >= 0) {
            return Status.EXCEEDED;
        }
        if (spent.compareTo(
                        BigDecimal.valueOf(budgetMicroCny)
                                .multiply(WARN_RATIO)
                                .setScale(0, RoundingMode.CEILING))
                >= 0) {
            return Status.WARN;
        }
        return Status.OK;
    }

    /**
     * 月度预算换算微元（ROUND_HALF_UP）：护栏比较全在整数域进行。
     *
     * @param budgetCny 月度预算（元，>0）
     * @return 微元整数
     */
    static long budgetMicroCny(BigDecimal budgetCny) {
        return budgetCny
                .multiply(BigDecimal.valueOf(1_000_000L))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /**
     * 指定月份的 UTC 月初时刻（月度口径的过滤下界）。
     *
     * @param month UTC 月份
     * @return 月初 Instant（00:00:00.000000）
     */
    public static Instant monthStartUtc(YearMonth month) {
        ZonedDateTime start = month.atDay(1).atStartOfDay(ZoneOffset.UTC);
        return start.toInstant();
    }
}

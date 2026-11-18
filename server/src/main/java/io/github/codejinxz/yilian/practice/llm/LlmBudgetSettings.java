package io.github.codejinxz.yilian.practice.llm;

import io.github.codejinxz.yilian.system.service.AppSettingService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 月度预算与模型定价存取（app_settings 键 {@code llm.budget}，预算护栏）：值 JSON 形状 {@code
 * {"schemaVersion":1,"monthlyBudgetCny":数字|null,"pricing":{"模型名":{"inputPerMillionCny":数字,"outputPerMillionCny":数字}}|null}}。
 *
 * <p>fail-closed 解析（与 {@link LlmProviderCatalog}/{@link TutorOverrides} 同款纪律）：键不存在、 JSON
 * 损坏、版本不识别一律按「未设预算 + 无定价」处理——未设预算=护栏不生效（现行为）， 无定价=全模型免费语义（成本落 NULL），绝不把脏配置放大成调用失败或预算误判。条目级校验：
 * 单价缺失/非正的定价条目跳过（WARN），不影响其余条目。读取频率低（三调用点 + 管理端页面）， 不走缓存（app_settings 本表不缓存，见 {@code
 * AppSettingService}）。
 */
@Component
public class LlmBudgetSettings {

    private static final Logger log = LoggerFactory.getLogger(LlmBudgetSettings.class);

    /** app_settings 键：月度预算与模型定价。 */
    public static final String SETTING_KEY = "llm.budget";

    /** 存储值当前结构版本；解析遇到其他版本按「未设预算 + 无定价」处理（fail-closed）。 */
    public static final int SCHEMA_VERSION = 1;

    private final AppSettingService appSettingService;
    private final ObjectMapper objectMapper;

    /**
     * 注入依赖。
     *
     * @param appSettingService 系统配置存取（app_settings 键值）
     * @param objectMapper JSON 解析（配置值）
     */
    public LlmBudgetSettings(AppSettingService appSettingService, ObjectMapper objectMapper) {
        this.appSettingService = appSettingService;
        this.objectMapper = objectMapper;
    }

    /**
     * 读取后的预算配置快照。
     *
     * @param monthlyBudgetCny 月度预算（元）；null=未设预算（护栏不生效）
     * @param pricing 模型定价表（永不返回 null，可能为空表=全模型无定价）
     */
    public record Config(BigDecimal monthlyBudgetCny, Map<String, LlmModelPricing> pricing) {

        /**
         * 是否已设有效预算（护栏是否生效的判据）。
         *
         * @return 已设正数预算返回 true
         */
        public boolean hasBudget() {
            return monthlyBudgetCny != null && monthlyBudgetCny.signum() > 0;
        }
    }

    /** 存储形状（app_settings 值 JSON，带 schemaVersion）。 */
    record StoredBudget(
            int schemaVersion, BigDecimal monthlyBudgetCny, Map<String, StoredPricing> pricing) {}

    /** 单条目存储形状。 */
    record StoredPricing(BigDecimal inputPerMillionCny, BigDecimal outputPerMillionCny) {}

    /**
     * 读取预算配置（fail-closed，永不返回 null）：键不存在/JSON 损坏/版本不识别按未设预算 + 无定价处理；
     * 单价非法的定价条目跳过（不影响其余条目），预算为非正数按未设处理。
     *
     * @return 配置快照
     */
    public Config read() {
        Optional<String> raw = appSettingService.findValue(SETTING_KEY);
        if (raw.isEmpty()) {
            return new Config(null, Map.of());
        }
        try {
            StoredBudget stored = objectMapper.readValue(raw.get(), StoredBudget.class);
            if (stored.schemaVersion() != SCHEMA_VERSION) {
                log.warn("llm.budget schemaVersion 不识别，按未设预算处理：{}", stored.schemaVersion());
                return new Config(null, Map.of());
            }
            BigDecimal budget =
                    stored.monthlyBudgetCny() != null && stored.monthlyBudgetCny().signum() > 0
                            ? stored.monthlyBudgetCny()
                            : null;
            Map<String, LlmModelPricing> pricing = new LinkedHashMap<>();
            if (stored.pricing() != null) {
                stored.pricing()
                        .forEach(
                                (model, entry) -> {
                                    LlmModelPricing parsed =
                                            new LlmModelPricing(
                                                    entry == null
                                                            ? null
                                                            : entry.inputPerMillionCny(),
                                                    entry == null
                                                            ? null
                                                            : entry.outputPerMillionCny());
                                    if (model == null || model.isBlank() || !parsed.isValid()) {
                                        log.warn("llm.budget 定价条目非法已跳过：model={}", model);
                                        return;
                                    }
                                    pricing.put(model, parsed);
                                });
            }
            return new Config(budget, Map.copyOf(pricing));
        } catch (JacksonException exception) {
            log.warn(
                    "llm.budget 配置损坏，按未设预算处理（护栏不生效）：error={}",
                    exception.getClass().getSimpleName());
            return new Config(null, Map.of());
        }
    }

    /**
     * 覆盖写入预算配置（末写胜出；并发写由 app_settings 单行主键收敛）。调用方保证值已过管理端校验。
     *
     * @param config 待写入配置（monthlyBudgetCny 为 null 或 pricing 空表=清除对应项）
     * @param operatorId 操作者 id（写审计字段，可空）
     */
    public void write(Config config, Long operatorId) {
        Map<String, StoredPricing> storedPricing =
                config.pricing() == null || config.pricing().isEmpty()
                        ? null
                        : toStoredPricing(config.pricing());
        String value =
                objectMapper.writeValueAsString(
                        new StoredBudget(
                                SCHEMA_VERSION,
                                config.hasBudget() ? config.monthlyBudgetCny() : null,
                                storedPricing));
        appSettingService.upsert(SETTING_KEY, value, "月度模型预算与定价（预算护栏）", operatorId);
    }

    /**
     * 定价表 → 存储形状（逐条目投影，键与单价原样透传）。
     *
     * @param pricing 归一后的定价表
     * @return 存储形状定价表
     */
    private static Map<String, StoredPricing> toStoredPricing(
            Map<String, LlmModelPricing> pricing) {
        Map<String, StoredPricing> stored = new LinkedHashMap<>();
        pricing.forEach(
                (model, entry) ->
                        stored.put(
                                model,
                                new StoredPricing(
                                        entry.inputPerMillionCny(), entry.outputPerMillionCny())));
        return stored;
    }
}

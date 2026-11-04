package io.github.codejinxz.yilian.practice.llm;

import io.github.codejinxz.yilian.system.service.AppSettingService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * provider 目录存取（022）：CC Switch 式多 provider 元数据持久化在 app_settings 两个键里—— {@code llm.providers}
 * （目录数组）与 {@code llm.active}（激活 id，激活唯一）。<strong>apiKey 不入目录</strong>：按 provider id 走环境变量 {@code
 * YILIAN_LLM_KEY_<大写ID>} 索引（AGENTS §3.4 密钥不入库），本类负责 env 索引口径。
 *
 * <p>解析 fail-closed：键缺失、JSON 损坏、schemaVersion 不识别、条目字段非法（未知协议/id 不合法）时跳过条目或整表 按空目录处理——空目录会让 {@code
 * LlmClientRegistry} 回退 legacy 环境变量轨，绝不把脏数据放大成调用失败。目录读取 频率低（答疑限流 5 次/分钟 + 管理端页面），不走缓存（app_settings
 * 本表不缓存，见 {@code AppSettingService}）。
 */
@Component
public class LlmProviderCatalog {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderCatalog.class);

    /**
     * app_settings 键：provider 目录（值
     * JSON：{"schemaVersion":1,"providers":[{id,name,protocol,baseUrl,model}]}）。
     */
    public static final String PROVIDERS_KEY = "llm.providers";

    /** app_settings 键：激活 provider（值 JSON：{"schemaVersion":1,"providerId":"…"}；激活唯一，可空=未激活）。 */
    public static final String ACTIVE_KEY = "llm.active";

    /** 存储值当前结构版本；解析遇到其他版本按空目录处理（fail-closed）。 */
    static final int SCHEMA_VERSION = 1;

    /** env 变量名前缀：{@code YILIAN_LLM_KEY_<大写ID>}（id 内连字符映射为下划线，保证是合法环境变量名）。 */
    static final String ENV_KEY_PREFIX = "YILIAN_LLM_KEY_";

    private final AppSettingService appSettingService;
    private final Environment environment;
    private final ObjectMapper objectMapper;

    /**
     * 注入依赖。
     *
     * @param appSettingService 系统配置存取（app_settings 键值）
     * @param environment 环境变量读取（按 provider id 索引密钥）
     * @param objectMapper JSON 解析（目录值）
     */
    public LlmProviderCatalog(
            AppSettingService appSettingService,
            Environment environment,
            ObjectMapper objectMapper) {
        this.appSettingService = appSettingService;
        this.environment = environment;
        this.objectMapper = objectMapper;
    }

    /** 目录快照：providers 为解析后的合法条目（保持存储顺序），activeId 可空=未激活。 */
    public record Catalog(List<LlmProviderDescriptor> providers, String activeId) {

        /**
         * 目录是否为空（空目录触发 legacy 环境变量轨回退）。
         *
         * @return 空目录返回 true
         */
        public boolean isEmpty() {
            return providers.isEmpty();
        }
    }

    /** 目录存储形状（app_settings 值 JSON，带 schemaVersion）。 */
    record StoredProviders(int schemaVersion, List<StoredProvider> providers) {}

    /** 激活存储形状。 */
    record StoredActive(int schemaVersion, String providerId) {}

    /** 单条目存储形状：protocol 为 wire 字符串（稳定契约），与枚举解耦。 */
    record StoredProvider(String id, String name, String protocol, String baseUrl, String model) {}

    /**
     * 读取目录快照（fail-closed）：任何解析异常按空目录处理。
     *
     * @return 目录快照（永不返回 null）
     */
    public Catalog read() {
        Optional<String> raw = appSettingService.findValue(PROVIDERS_KEY);
        if (raw.isEmpty()) {
            return new Catalog(List.of(), readActiveId().orElse(null));
        }
        try {
            StoredProviders stored = objectMapper.readValue(raw.get(), StoredProviders.class);
            if (stored.schemaVersion() != SCHEMA_VERSION || stored.providers() == null) {
                return new Catalog(List.of(), readActiveId().orElse(null));
            }
            List<LlmProviderDescriptor> providers =
                    stored.providers().stream()
                            .map(this::toDescriptor)
                            .flatMap(Optional::stream)
                            .toList();
            return new Catalog(providers, readActiveId().orElse(null));
        } catch (JacksonException exception) {
            log.warn(
                    "llm.providers 配置损坏，按空目录处理（legacy 轨回退）：error={}",
                    exception.getClass().getSimpleName());
            return new Catalog(List.of(), readActiveId().orElse(null));
        }
    }

    /**
     * 覆盖写入整个目录（末写胜出；并发写由 app_settings 单行主键收敛）。
     *
     * @param providers 完整目录（调用方保证已过 id/协议校验）
     * @param operatorId 操作者 id（写审计字段，可空）
     */
    public void saveProviders(List<LlmProviderDescriptor> providers, Long operatorId) {
        List<StoredProvider> stored =
                providers.stream()
                        .map(
                                descriptor ->
                                        new StoredProvider(
                                                descriptor.id(),
                                                descriptor.name(),
                                                descriptor.protocol().wire(),
                                                descriptor.baseUrl(),
                                                descriptor.model()))
                        .toList();
        String value = objectMapper.writeValueAsString(new StoredProviders(SCHEMA_VERSION, stored));
        appSettingService.upsert(
                PROVIDERS_KEY, value, "LLM provider 目录（022，密钥走 env 索引不入库）", operatorId);
    }

    /**
     * 写入激活 provider（激活唯一；由管理端 service 保证 id 存在才调用）。
     *
     * @param providerId 激活的 provider id
     * @param operatorId 操作者 id（可空）
     */
    public void saveActive(String providerId, Long operatorId) {
        String value =
                objectMapper.writeValueAsString(new StoredActive(SCHEMA_VERSION, providerId));
        appSettingService.upsert(ACTIVE_KEY, value, "激活的 LLM provider（022）", operatorId);
    }

    /**
     * 按 id 读取环境变量密钥：<code>YILIAN_LLM_KEY_&lt;大写ID&gt;</code>（id 内连字符映射为下划线，如 {@code zcode-gateway}
     * → {@code YILIAN_LLM_KEY_ZCODE_GATEWAY}）。
     *
     * @param providerId provider id
     * @return 密钥；未配置返回空串（不返回 null）
     */
    public String envKey(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return "";
        }
        String variable = ENV_KEY_PREFIX + providerId.replace('-', '_').toUpperCase(Locale.ROOT);
        return environment.getProperty(variable, "");
    }

    /**
     * 读取激活 id（独立键，fail-closed）。
     *
     * @return 激活 id；未激活或损坏时为空
     */
    private Optional<String> readActiveId() {
        Optional<String> raw = appSettingService.findValue(ACTIVE_KEY);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            StoredActive stored = objectMapper.readValue(raw.get(), StoredActive.class);
            if (stored.schemaVersion() != SCHEMA_VERSION || stored.providerId() == null) {
                return Optional.empty();
            }
            return Optional.of(stored.providerId().trim());
        } catch (JacksonException exception) {
            log.warn("llm.active 配置损坏，按未激活处理：error={}", exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * 存储条目 → 目录条目；id 非法或协议未知返回 empty（跳过该条目，fail-closed，不让脏数据进入装配）。
     *
     * @param stored 存储条目
     * @return 目录条目
     */
    private Optional<LlmProviderDescriptor> toDescriptor(StoredProvider stored) {
        if (stored == null) {
            return Optional.empty();
        }
        LlmProviderDescriptor descriptor =
                new LlmProviderDescriptor(
                        stored.id(),
                        stored.name(),
                        LlmProtocol.fromWire(stored.protocol()).orElse(null),
                        stored.baseUrl(),
                        stored.model());
        if (!descriptor.hasValidId() || descriptor.protocol() == null) {
            log.warn("llm.providers 条目非法已跳过：id={}, protocol={}", stored.id(), stored.protocol());
            return Optional.empty();
        }
        return Optional.of(descriptor);
    }
}

package io.github.codejinxz.yilian.practice.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link LlmClientRegistry} 实现：每次解析读 provider 目录快照，以快照为指纹做运行时缓存——目录（含激活
 * 态）未变时复用已装配的客户端，目录任何变更（增删改/激活切换）下下次调用自动换轨，「目录变更失效」无需重启也无 需显式失效钩子（app_settings 单行读很廉价，答疑另有 5
 * 次/分钟限流）。
 *
 * <p>装配选择：按激活条目 protocol 构造 {@link AnthropicMessagesLlmClient} / {@link OpenAiChatLlmClient} /
 * {@link OpenAiResponsesLlmClient}；legacy 轨（目录空 + 环境变量齐备）按 {@link LlmProperties#legacyProtocol()}
 * 构造并 惰性缓存（环境变量启动期固定）。客户端构造失败（如脏端点）按「无可用配置」处理（fail-closed），不放大成 500。
 */
@Component
public class LlmClientRegistryImpl implements LlmClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(LlmClientRegistryImpl.class);

    private final LlmProviderCatalog providerCatalog;
    private final LlmProperties legacyProperties;

    /** legacy 轨客户端惰性缓存：环境变量启动期固定，构造一次复用。 */
    private final AtomicReference<LlmChatClient> legacyClient = new AtomicReference<>();

    /** 目录模式解析缓存：指纹=目录快照（值相等语义），变更即失效重建。 */
    private final AtomicReference<CachedResolution> cached = new AtomicReference<>();

    /** 一次解析的结果：可用句柄与管理端视图可以同时存在/缺席（凭证不齐=视图在、句柄缺）。 */
    private record Resolution(Optional<ChatHandle> handle, Optional<EffectiveConfig> config) {}

    /** 缓存条目：目录快照指纹 + 解析结果。 */
    private record CachedResolution(
            LlmProviderCatalog.Catalog fingerprint, Resolution resolution) {}

    /**
     * 注入依赖。
     *
     * @param providerCatalog provider 目录存取
     * @param legacyProperties legacy 环境变量配置（兼容轨）
     */
    public LlmClientRegistryImpl(
            LlmProviderCatalog providerCatalog, LlmProperties legacyProperties) {
        this.providerCatalog = providerCatalog;
        this.legacyProperties = legacyProperties;
    }

    @Override
    public Optional<ChatHandle> chatHandle() {
        return resolve().handle();
    }

    @Override
    public Optional<EffectiveConfig> effectiveConfig() {
        return resolve().config();
    }

    @Override
    public boolean directoryMode() {
        return !providerCatalog.read().isEmpty();
    }

    /**
     * 解析当前生效客户端（带目录指纹缓存）。
     *
     * @return 解析结果（永不返回 null）
     */
    private Resolution resolve() {
        LlmProviderCatalog.Catalog catalog = providerCatalog.read();
        if (catalog.isEmpty()) {
            return resolveLegacy();
        }
        CachedResolution cachedEntry = cached.get();
        if (cachedEntry != null && cachedEntry.fingerprint().equals(catalog)) {
            return cachedEntry.resolution();
        }
        Resolution resolution = resolveDirectory(catalog);
        cached.set(new CachedResolution(catalog, resolution));
        return resolution;
    }

    /**
     * legacy 兼容轨：目录为空且 {@code YILIAN_LLM_*} 凭证齐备 → 视为单一隐式 provider（读路径兼容 017/021，协议按 {@code
     * YILIAN_LLM_PROVIDER}）；否则无可用配置（功能不存在）。
     *
     * @return 解析结果
     */
    private Resolution resolveLegacy() {
        if (!legacyProperties.hasCredentials()) {
            return new Resolution(Optional.empty(), Optional.empty());
        }
        EffectiveConfig config =
                new EffectiveConfig(
                        LEGACY_PROVIDER_ID,
                        legacyProperties.legacyProtocol().wire(),
                        legacyProperties.baseUrl(),
                        legacyProperties.model(),
                        !legacyProperties.apiKey().isEmpty(),
                        true);
        LlmChatClient client =
                legacyClient.updateAndGet(
                        existing ->
                                existing != null
                                        ? existing
                                        : createSafely(
                                                legacyProperties.legacyProtocol(),
                                                legacyProperties.asEndpoint()));
        if (client == null) {
            return new Resolution(Optional.empty(), Optional.of(config));
        }
        return new Resolution(
                Optional.of(new ChatHandle(client, LEGACY_PROVIDER_ID, legacyProperties.model())),
                Optional.of(config));
    }

    /**
     * 目录模式：未激活或激活 id 失效 → 无可用配置（fail-closed，绝不静默落到第一个条目）；激活条目凭证不齐 → 句柄缺 而视图在（管理员能看到「差什么」）。
     *
     * @param catalog 目录快照
     * @return 解析结果
     */
    private Resolution resolveDirectory(LlmProviderCatalog.Catalog catalog) {
        if (catalog.activeId() == null) {
            return new Resolution(Optional.empty(), Optional.empty());
        }
        Optional<LlmProviderDescriptor> active =
                catalog.providers().stream()
                        .filter(provider -> provider.id().equals(catalog.activeId()))
                        .findFirst();
        if (active.isEmpty()) {
            return new Resolution(Optional.empty(), Optional.empty());
        }
        LlmProviderDescriptor descriptor = active.get();
        String envKey = providerCatalog.envKey(descriptor.id());
        boolean credentialsComplete = descriptor.hasCredentials(!envKey.isEmpty());
        EffectiveConfig config =
                new EffectiveConfig(
                        descriptor.id(),
                        descriptor.protocol().wire(),
                        descriptor.baseUrl(),
                        descriptor.model(),
                        !envKey.isEmpty(),
                        credentialsComplete);
        if (!credentialsComplete) {
            return new Resolution(Optional.empty(), Optional.of(config));
        }
        LlmChatClient client =
                createSafely(
                        descriptor.protocol(),
                        new LlmEndpoint(descriptor.baseUrl(), envKey, descriptor.model(), null));
        if (client == null) {
            return new Resolution(Optional.empty(), Optional.of(config));
        }
        log.info(
                "LLM 客户端就绪：providerId={}, protocol={}, model={}",
                descriptor.id(),
                descriptor.protocol().wire(),
                descriptor.model());
        return new Resolution(
                Optional.of(new ChatHandle(client, descriptor.id(), descriptor.model())),
                Optional.of(config));
    }

    /**
     * 按协议装配客户端；构造异常（脏端点等）吞掉并按空处理——装配失败是部署配置问题，应表现为「功能不存在」 而不是把答疑请求打成 500。
     *
     * @param protocol 对话协议
     * @param endpoint 端点参数
     * @return 客户端；构造失败返回 null
     */
    private LlmChatClient createSafely(LlmProtocol protocol, LlmEndpoint endpoint) {
        try {
            return switch (protocol) {
                case ANTHROPIC_MESSAGES -> new AnthropicMessagesLlmClient(endpoint);
                case OPENAI_CHAT -> new OpenAiChatLlmClient(endpoint);
                case OPENAI_RESPONSES -> new OpenAiResponsesLlmClient(endpoint);
            };
        } catch (RuntimeException exception) {
            log.warn(
                    "LLM 客户端装配失败，按功能不存在处理：protocol={}, error={}",
                    protocol.wire(),
                    exception.getClass().getSimpleName());
            return null;
        }
    }
}

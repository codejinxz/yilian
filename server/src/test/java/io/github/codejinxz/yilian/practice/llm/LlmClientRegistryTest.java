package io.github.codejinxz.yilian.practice.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import io.github.codejinxz.yilian.system.service.AppSettingService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * {@link LlmClientRegistryImpl} 单测 [AC-1]/[AC-2]：按激活 provider 的 protocol 装配三协议客户端、运行时缓存（目录
 * 未变复用同一实例、目录变更即失效换轨）、legacy 族回退（目录空：tutor 覆盖轨 &gt; YILIAN_LLM_* 三件套）与三态门（无可用配置 → chatHandle
 * 空），凭证口径按协议分（anthropic-messages key 可空，openai 两轨 key 必填）；覆盖变更即时换轨（028）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LlmClientRegistryTest {

    private static final LlmProviderDescriptor ZAI =
            new LlmProviderDescriptor(
                    "zai",
                    "ZCode 网关",
                    LlmProtocol.ANTHROPIC_MESSAGES,
                    "http://127.0.0.1:47860/gateway/zcode",
                    "glm-5.3");

    private static final LlmProviderDescriptor ZHIPU =
            new LlmProviderDescriptor(
                    "zhipu",
                    "智谱",
                    LlmProtocol.OPENAI_CHAT,
                    "https://open.bigmodel.cn/api/paas/v4",
                    "glm-4.7");

    private static final LlmProviderDescriptor OPENAI_RESPONSES =
            new LlmProviderDescriptor(
                    "oai-responses",
                    "OpenAI Responses",
                    LlmProtocol.OPENAI_RESPONSES,
                    "https://api.openai.com",
                    "gpt-5.1");

    @Mock private LlmProviderCatalog providerCatalog;

    @Mock private LlmProviderKeys providerKeys;

    @Mock private AppSettingService appSettingService;

    private LlmProperties legacyProperties;

    private LlmClientRegistryImpl registry;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // 缺省 legacy 轨未配置（base-url 空）；目录侧缺省空、tutor 覆盖缺省无。
        legacyProperties = new LlmProperties("", "", null, null, null);
        registry = newRegistry();
        lenient().when(providerCatalog.read()).thenReturn(emptyCatalog());
        lenient().when(providerCatalog.envKey("zai")).thenReturn("");
        lenient().when(providerCatalog.envKey("zhipu")).thenReturn("sk-zhipu");
        lenient().when(providerCatalog.envKey("oai-responses")).thenReturn("sk-oai");
        // 027B 解析链：单测里密钥链退化为纯 env 轨（DB 行由集成测试覆盖），保持既有断言语义。
        lenient()
                .doAnswer(
                        invocation -> {
                            String env = providerCatalog.envKey(invocation.getArgument(0));
                            return env.isEmpty() ? Optional.empty() : Optional.of(env);
                        })
                .when(providerKeys)
                .resolve(any());
        // tutor 预留行（028）：单测无入库行，resolveDecrypted 返回空。
        lenient()
                .when(providerKeys.resolveDecrypted(LlmProviderKeys.TUTOR_PROVIDER_ID))
                .thenReturn(Optional.empty());
        lenient().when(providerKeys.stateFingerprint()).thenReturn("");
        lenient()
                .when(appSettingService.findValue(TutorOverrides.SETTING_KEY))
                .thenReturn(Optional.empty());
    }

    /**
     * 以当前 legacy 配置构造注册表（tutor 覆盖解析走真实 TutorOverrides，读取打桩的 app_settings）。
     *
     * @return 注册表实例
     */
    private LlmClientRegistryImpl newRegistry() {
        return new LlmClientRegistryImpl(
                providerCatalog,
                providerKeys,
                legacyProperties,
                new TutorOverrides(appSettingService, objectMapper));
    }

    /** [AC-1] 激活 provider 按协议装配：anthropic-messages→Anthropic 客户端，openai-chat→OpenAI 兼容客户端。 */
    @Test
    void assemblesClientByActiveProviderProtocol_ac1() {
        stubDirectory(List.of(ZAI, ZHIPU), "zai");
        assertThat(registry.chatHandle())
                .hasValueSatisfying(
                        handle -> {
                            assertThat(handle.client())
                                    .isInstanceOf(AnthropicMessagesLlmClient.class);
                            assertThat(handle.providerId()).isEqualTo("zai");
                            assertThat(handle.model()).isEqualTo("glm-5.3");
                        });

        stubDirectory(List.of(ZAI, ZHIPU), "zhipu");
        assertThat(registry.chatHandle())
                .hasValueSatisfying(
                        handle -> {
                            assertThat(handle.client()).isInstanceOf(OpenAiChatLlmClient.class);
                            assertThat(handle.providerId()).isEqualTo("zhipu");
                        });
    }

    /** [AC-1] 激活 openai-responses 条目装配新 Responses 客户端。 */
    @Test
    void assemblesResponsesClientForResponsesProtocol_ac1() {
        stubDirectory(List.of(OPENAI_RESPONSES), "oai-responses");

        assertThat(registry.chatHandle())
                .hasValueSatisfying(
                        handle ->
                                assertThat(handle.client())
                                        .isInstanceOf(OpenAiResponsesLlmClient.class));
    }

    /** [AC-2] legacy env 轨回退：目录空且无覆盖 + YILIAN_LLM_* 齐备 → 单一隐式 provider（providerId=legacy）。 */
    @Test
    void fallsBackToLegacyEnvWhenDirectoryEmpty_ac2() {
        legacyProperties =
                new LlmProperties(
                        "https://open.bigmodel.cn/api/paas/v4",
                        "sk-legacy",
                        "glm-4.7",
                        Duration.ofSeconds(15),
                        LlmProperties.LlmProvider.OPENAI);
        registry = newRegistry();

        assertThat(registry.chatHandle())
                .hasValueSatisfying(
                        handle -> {
                            assertThat(handle.client()).isInstanceOf(OpenAiChatLlmClient.class);
                            assertThat(handle.providerId())
                                    .isEqualTo(LlmClientRegistry.LEGACY_PROVIDER_ID);
                        });
        assertThat(registry.effectiveConfig())
                .hasValueSatisfying(
                        config -> {
                            assertThat(config.providerId())
                                    .isEqualTo(LlmClientRegistry.LEGACY_PROVIDER_ID);
                            assertThat(config.protocol()).isEqualTo("openai-chat");
                            assertThat(config.baseUrl())
                                    .isEqualTo("https://open.bigmodel.cn/api/paas/v4");
                            assertThat(config.hasKey()).isTrue();
                            assertThat(config.credentialsComplete()).isTrue();
                        });
        assertThat(registry.directoryMode()).isFalse();
    }

    /** [AC-2] legacy env 轨的 anthropic 轨：仅 base-url（key 可空）即可用。 */
    @Test
    void legacyAnthropicTrackRequiresOnlyBaseUrl_ac2() {
        legacyProperties =
                new LlmProperties(
                        "http://127.0.0.1:47860/gateway/zcode",
                        "",
                        "glm-5.3",
                        null,
                        LlmProperties.LlmProvider.ANTHROPIC);
        registry = newRegistry();

        assertThat(registry.chatHandle())
                .hasValueSatisfying(
                        handle ->
                                assertThat(handle.client())
                                        .isInstanceOf(AnthropicMessagesLlmClient.class));
        assertThat(registry.effectiveConfig())
                .hasValueSatisfying(
                        config -> {
                            assertThat(config.hasKey()).isFalse();
                            assertThat(config.credentialsComplete()).isTrue();
                        });
    }

    /** [AC-2] tutor 覆盖轨优先于 legacy env 轨：覆盖端点/模型生效，密钥链 DB(tutor 预留行) &gt; YILIAN_LLM_API_KEY env。 */
    @Test
    void tutorOverrideRailBeatsLegacyEnvRail_ac2() {
        legacyProperties =
                new LlmProperties(
                        "https://env.example.com/v4",
                        "sk-env-legacy",
                        "glm-4.7",
                        null,
                        LlmProperties.LlmProvider.OPENAI);
        registry = newRegistry();
        when(appSettingService.findValue(TutorOverrides.SETTING_KEY))
                .thenReturn(
                        Optional.of(
                                "{\"schemaVersion\":1,\"enabled\":true,"
                                        + "\"baseUrl\":\"http://10.0.0.8:8080/v4\",\"model\":\"glm-5.3-flash\"}"));

        assertThat(registry.chatHandle())
                .hasValueSatisfying(
                        handle -> {
                            assertThat(handle.client()).isInstanceOf(OpenAiChatLlmClient.class);
                            assertThat(handle.model()).isEqualTo("glm-5.3-flash");
                        });
        assertThat(registry.effectiveConfig())
                .hasValueSatisfying(
                        config -> {
                            assertThat(config.baseUrl()).isEqualTo("http://10.0.0.8:8080/v4");
                            assertThat(config.model()).isEqualTo("glm-5.3-flash");
                            // 密钥链：无 tutor 入库行 → 回退 legacy env 密钥。
                            assertThat(config.hasKey()).isTrue();
                            assertThat(config.credentialsComplete()).isTrue();
                        });

        // DB tutor 预留行解密值优先于 env 密钥（028 密钥链，复用 027B 解析口）。
        when(providerKeys.resolveDecrypted(LlmProviderKeys.TUTOR_PROVIDER_ID))
                .thenReturn(Optional.of("sk-tutor-db"));
        assertThat(registry.chatHandle()).isPresent();
        assertThat(registry.effectiveConfig())
                .hasValueSatisfying(config -> assertThat(config.hasKey()).isTrue());
    }

    /** [AC-2] 覆盖模型缺省回退 env 模型：只覆盖端点时模型沿用 YILIAN_LLM_MODEL。 */
    @Test
    void tutorOverrideModelFallsBackToEnvModel_ac2() {
        legacyProperties =
                new LlmProperties(
                        "https://env.example.com/v4",
                        "sk-env-legacy",
                        "glm-4.7",
                        null,
                        LlmProperties.LlmProvider.OPENAI);
        registry = newRegistry();
        when(appSettingService.findValue(TutorOverrides.SETTING_KEY))
                .thenReturn(
                        Optional.of(
                                "{\"schemaVersion\":1,\"enabled\":true,"
                                        + "\"baseUrl\":\"http://10.0.0.8:8080/v4\",\"model\":null}"));

        assertThat(registry.effectiveConfig())
                .hasValueSatisfying(
                        config -> {
                            assertThat(config.baseUrl()).isEqualTo("http://10.0.0.8:8080/v4");
                            assertThat(config.model()).isEqualTo("glm-4.7");
                        });
    }

    /** [AC-2] 覆盖轨缺密钥（openai 轨）：视图在、句柄缺（三态门不谎报可用）。 */
    @Test
    void tutorOverrideRailWithoutKeyYieldsViewWithoutHandle_ac2() {
        legacyProperties =
                new LlmProperties(
                        "https://env.example.com/v4",
                        "",
                        "glm-4.7",
                        null,
                        LlmProperties.LlmProvider.OPENAI);
        registry = newRegistry();
        when(appSettingService.findValue(TutorOverrides.SETTING_KEY))
                .thenReturn(
                        Optional.of(
                                "{\"schemaVersion\":1,\"enabled\":true,"
                                        + "\"baseUrl\":\"http://10.0.0.8:8080/v4\",\"model\":null}"));

        assertThat(registry.chatHandle()).isEmpty();
        assertThat(registry.effectiveConfig())
                .hasValueSatisfying(
                        config -> {
                            assertThat(config.baseUrl()).isEqualTo("http://10.0.0.8:8080/v4");
                            assertThat(config.hasKey()).isFalse();
                            assertThat(config.credentialsComplete()).isFalse();
                        });
    }

    /** [AC-2] 覆盖变更即时换轨：指纹含覆盖状态——同一注册表实例上保存/清除覆盖，下一次解析即换轨/回落 env 轨。 */
    @Test
    void tutorOverrideChangeSwitchesRailImmediately_ac2() {
        legacyProperties =
                new LlmProperties(
                        "https://env.example.com/v4",
                        "sk-env-legacy",
                        "glm-4.7",
                        null,
                        LlmProperties.LlmProvider.OPENAI);
        registry = newRegistry();

        // 初始：无覆盖 → env 轨（端点=env 值）。
        assertThat(registry.effectiveConfig())
                .hasValueSatisfying(
                        config ->
                                assertThat(config.baseUrl())
                                        .isEqualTo("https://env.example.com/v4"));

        // 保存覆盖 → 下一次解析即换覆盖轨（无需重启/显式失效）。
        when(appSettingService.findValue(TutorOverrides.SETTING_KEY))
                .thenReturn(
                        Optional.of(
                                "{\"schemaVersion\":1,\"enabled\":true,"
                                        + "\"baseUrl\":\"http://10.0.0.8:8080/v4\",\"model\":\"glm-5.3-flash\"}"));
        assertThat(registry.effectiveConfig())
                .hasValueSatisfying(
                        config -> {
                            assertThat(config.baseUrl()).isEqualTo("http://10.0.0.8:8080/v4");
                            assertThat(config.model()).isEqualTo("glm-5.3-flash");
                        });
        assertThat(registry.chatHandle())
                .hasValueSatisfying(
                        handle -> assertThat(handle.model()).isEqualTo("glm-5.3-flash"));

        // 修改覆盖模型 → 即时换轨到新模型（028 即时生效语义）。
        when(appSettingService.findValue(TutorOverrides.SETTING_KEY))
                .thenReturn(
                        Optional.of(
                                "{\"schemaVersion\":1,\"enabled\":true,"
                                        + "\"baseUrl\":\"http://10.0.0.8:8080/v4\",\"model\":\"glm-4.7-air\"}"));
        assertThat(registry.chatHandle())
                .hasValueSatisfying(handle -> assertThat(handle.model()).isEqualTo("glm-4.7-air"));

        // 清除覆盖 → 回落 env 轨。
        when(appSettingService.findValue(TutorOverrides.SETTING_KEY))
                .thenReturn(
                        Optional.of(
                                "{\"schemaVersion\":1,\"enabled\":true,\"baseUrl\":null,\"model\":null}"));
        assertThat(registry.effectiveConfig())
                .hasValueSatisfying(
                        config ->
                                assertThat(config.baseUrl())
                                        .isEqualTo("https://env.example.com/v4"));
    }

    /** [AC-2] 022 语义不变：目录非空时覆盖轨休眠——目录未激活仍是 fail-closed（不回落覆盖/env）。 */
    @Test
    void overrideRailDormantWhenDirectoryExists_ac2() {
        legacyProperties =
                new LlmProperties(
                        "https://env.example.com/v4",
                        "sk-env-legacy",
                        "glm-4.7",
                        null,
                        LlmProperties.LlmProvider.OPENAI);
        registry = newRegistry();
        when(appSettingService.findValue(TutorOverrides.SETTING_KEY))
                .thenReturn(
                        Optional.of(
                                "{\"schemaVersion\":1,\"enabled\":true,"
                                        + "\"baseUrl\":\"http://10.0.0.8:8080/v4\",\"model\":null}"));
        stubDirectory(List.of(ZAI), null);

        assertThat(registry.chatHandle()).isEmpty();
        assertThat(registry.effectiveConfig()).isEmpty();
    }

    /** [AC-2] 三态门：目录空且 legacy 未配置 / 目录未激活 / 激活 id 失效 → chatHandle 与视图皆空。 */
    @Test
    void yieldsNoHandleWhenNothingUsable_ac2() {
        // 目录空且 legacy 未配置。
        assertThat(registry.chatHandle()).isEmpty();
        assertThat(registry.effectiveConfig()).isEmpty();

        // 目录非空但未激活。
        stubDirectory(List.of(ZAI), null);
        assertThat(registry.chatHandle()).isEmpty();
        assertThat(registry.effectiveConfig()).isEmpty();

        // 激活 id 指向不存在的条目。
        stubDirectory(List.of(ZAI), "ghost");
        assertThat(registry.chatHandle()).isEmpty();
        assertThat(registry.directoryMode()).isTrue();
    }

    /** [AC-2] 凭证口径按协议分：openai 缺 key → 句柄缺而视图在（hasKey=false、齐备性 false）；anthropic 无 key 可用。 */
    @Test
    void appliesProtocolSpecificCredentialRules_ac2() {
        // zhipu 的 env key 已在 setUp 打桩，这里覆盖为空。
        when(providerCatalog.envKey("zhipu")).thenReturn("");
        stubDirectory(List.of(ZHIPU), "zhipu");

        assertThat(registry.chatHandle()).isEmpty();
        assertThat(registry.effectiveConfig())
                .hasValueSatisfying(
                        config -> {
                            assertThat(config.hasKey()).isFalse();
                            assertThat(config.credentialsComplete()).isFalse();
                            assertThat(config.baseUrl())
                                    .isEqualTo("https://open.bigmodel.cn/api/paas/v4");
                        });

        // anthropic-messages 轨 key 可空：无 key 也可用。
        when(providerCatalog.envKey("zai")).thenReturn("");
        stubDirectory(List.of(ZAI), "zai");
        assertThat(registry.chatHandle()).isPresent();
        assertThat(registry.effectiveConfig())
                .hasValueSatisfying(
                        config -> {
                            assertThat(config.hasKey()).isFalse();
                            assertThat(config.credentialsComplete()).isTrue();
                        });
    }

    /** [AC-1] 运行时缓存：目录未变时复用同一客户端实例；目录（含激活态）变更即失效换轨。 */
    @Test
    void cachesClientUntilCatalogChanges_ac1() {
        stubDirectory(List.of(ZAI, ZHIPU), "zai");

        Optional<LlmClientRegistry.ChatHandle> first = registry.chatHandle();
        Optional<LlmClientRegistry.ChatHandle> second = registry.chatHandle();
        assertThat(first).isPresent();
        assertThat(first).hasValue(second.orElseThrow());

        // 激活切换 → 下次解析换轨为新协议客户端。
        stubDirectory(List.of(ZAI, ZHIPU), "zhipu");
        Optional<LlmClientRegistry.ChatHandle> third = registry.chatHandle();
        assertThat(third)
                .hasValueSatisfying(
                        handle -> {
                            assertThat(handle.client()).isInstanceOf(OpenAiChatLlmClient.class);
                            assertThat(handle.providerId()).isEqualTo("zhipu");
                        });
        assertThat(third).isNotEqualTo(first);
    }

    /**
     * 打桩目录快照（lenient 覆盖缺省空目录）。
     *
     * @param providers 目录条目
     * @param activeId 激活 id（可空）
     */
    private void stubDirectory(List<LlmProviderDescriptor> providers, String activeId) {
        lenient()
                .when(providerCatalog.read())
                .thenReturn(new LlmProviderCatalog.Catalog(providers, activeId));
    }

    /**
     * 空目录快照。
     *
     * @return 空目录
     */
    private static LlmProviderCatalog.Catalog emptyCatalog() {
        return new LlmProviderCatalog.Catalog(List.of(), null);
    }
}

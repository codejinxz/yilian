package io.github.codejinxz.yilian.practice.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * {@link LlmClientRegistryImpl} 单测 [AC-1]/[AC-2]：按激活 provider 的 protocol 装配三协议客户端、运行时缓存（目录
 * 未变复用同一实例、目录变更即失效换轨）、legacy 单 provider 回退（目录空 + YILIAN_LLM_* 齐备）与三态门（无可用配置 → chatHandle
 * 空），凭证口径按协议分（anthropic-messages key 可空，openai 两轨 key 必填）。
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

    private LlmProperties legacyProperties;

    private LlmClientRegistryImpl registry;

    @BeforeEach
    void setUp() {
        // 缺省 legacy 轨未配置（base-url 空）；目录侧缺省空。
        legacyProperties = new LlmProperties("", "", null, null, null);
        registry = new LlmClientRegistryImpl(providerCatalog, legacyProperties);
        lenient().when(providerCatalog.read()).thenReturn(emptyCatalog());
        lenient().when(providerCatalog.envKey("zai")).thenReturn("");
        lenient().when(providerCatalog.envKey("zhipu")).thenReturn("sk-zhipu");
        lenient().when(providerCatalog.envKey("oai-responses")).thenReturn("sk-oai");
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

    /** [AC-2] legacy 回退：目录空 + YILIAN_LLM_* 齐备 → 单一隐式 provider（providerId=legacy）。 */
    @Test
    void fallsBackToLegacyEnvWhenDirectoryEmpty_ac2() {
        legacyProperties =
                new LlmProperties(
                        "https://open.bigmodel.cn/api/paas/v4",
                        "sk-legacy",
                        "glm-4.7",
                        Duration.ofSeconds(15),
                        LlmProperties.LlmProvider.OPENAI);
        registry = new LlmClientRegistryImpl(providerCatalog, legacyProperties);

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
                            assertThat(config.hasKey()).isTrue();
                            assertThat(config.credentialsComplete()).isTrue();
                        });
        assertThat(registry.directoryMode()).isFalse();
    }

    /** [AC-2] legacy 回退的 anthropic 轨：仅 base-url（key 可空）即可用。 */
    @Test
    void legacyAnthropicTrackRequiresOnlyBaseUrl_ac2() {
        legacyProperties =
                new LlmProperties(
                        "http://127.0.0.1:47860/gateway/zcode",
                        "",
                        "glm-5.3",
                        null,
                        LlmProperties.LlmProvider.ANTHROPIC);
        registry = new LlmClientRegistryImpl(providerCatalog, legacyProperties);

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

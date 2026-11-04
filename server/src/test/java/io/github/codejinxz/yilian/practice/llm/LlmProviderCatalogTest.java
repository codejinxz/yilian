package io.github.codejinxz.yilian.practice.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.codejinxz.yilian.system.service.AppSettingService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.env.Environment;

import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

/**
 * {@link LlmProviderCatalog} 单测 [AC-2]：目录/激活两键的解析（fail-closed——键缺失、JSON 损坏、版本不识别、条目非法
 * 均按空/跳过处理）、写入形状（schemaVersion + wire 协议）与 env 密钥索引口径（YILIAN_LLM_KEY_&lt;大写ID&gt;，连字符 →下划线）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LlmProviderCatalogTest {

    @Mock private AppSettingService appSettingService;

    @Mock private Environment environment;

    private LlmProviderCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new LlmProviderCatalog(appSettingService, environment, new ObjectMapper());
    }

    /** [AC-2] 键缺失：空目录 + 未激活（legacy 回退的前提形态）。 */
    @Test
    void returnsEmptyCatalogWhenKeysAbsent_ac2() {
        when(appSettingService.findValue(LlmProviderCatalog.PROVIDERS_KEY))
                .thenReturn(Optional.empty());
        when(appSettingService.findValue(LlmProviderCatalog.ACTIVE_KEY))
                .thenReturn(Optional.empty());

        LlmProviderCatalog.Catalog snapshot = catalog.read();

        assertThat(snapshot.isEmpty()).isTrue();
        assertThat(snapshot.activeId()).isNull();
    }

    /** [AC-2] 合法 JSON：条目与激活 id 解析落位，存储顺序保持。 */
    @Test
    void parsesProvidersAndActiveId_ac2() {
        when(appSettingService.findValue(LlmProviderCatalog.PROVIDERS_KEY))
                .thenReturn(
                        Optional.of(
                                "{\"schemaVersion\":1,\"providers\":["
                                        + "{\"id\":\"zai\",\"name\":\"ZCode 网关\",\"protocol\":\"anthropic-messages\","
                                        + "\"baseUrl\":\"http://127.0.0.1:47860/gateway/zcode\",\"model\":\"glm-5.3\"},"
                                        + "{\"id\":\"zhipu\",\"name\":\"智谱\",\"protocol\":\"openai-chat\","
                                        + "\"baseUrl\":\"https://open.bigmodel.cn/api/paas/v4\",\"model\":\"glm-4.7\"}]}"));
        when(appSettingService.findValue(LlmProviderCatalog.ACTIVE_KEY))
                .thenReturn(Optional.of("{\"schemaVersion\":1,\"providerId\":\"zai\"}"));

        LlmProviderCatalog.Catalog snapshot = catalog.read();

        assertThat(snapshot.providers()).hasSize(2);
        assertThat(snapshot.providers().get(0).id()).isEqualTo("zai");
        assertThat(snapshot.providers().get(0).protocol())
                .isEqualTo(LlmProtocol.ANTHROPIC_MESSAGES);
        assertThat(snapshot.providers().get(1).protocol()).isEqualTo(LlmProtocol.OPENAI_CHAT);
        assertThat(snapshot.activeId()).isEqualTo("zai");
    }

    /** [AC-2] fail-closed：JSON 损坏或 schemaVersion 不识别 → 空目录（不放大成调用失败）。 */
    @Test
    void failsClosedOnCorruptOrUnknownVersion_ac2() {
        when(appSettingService.findValue(LlmProviderCatalog.PROVIDERS_KEY))
                .thenReturn(Optional.of("{not-json"));
        assertThat(catalog.read().isEmpty()).isTrue();

        when(appSettingService.findValue(LlmProviderCatalog.PROVIDERS_KEY))
                .thenReturn(Optional.of("{\"schemaVersion\":99,\"providers\":[]}"));
        assertThat(catalog.read().isEmpty()).isTrue();
    }

    /** [AC-2] 非法条目跳过（未知协议/id 形态非法），合法条目保留——脏数据不进入装配。 */
    @Test
    void skipsInvalidEntries_ac2() {
        when(appSettingService.findValue(LlmProviderCatalog.PROVIDERS_KEY))
                .thenReturn(
                        Optional.of(
                                "{\"schemaVersion\":1,\"providers\":["
                                        + "{\"id\":\"bad1\",\"name\":\"未知协议\",\"protocol\":\"gemini\",\"baseUrl\":\"https://x\",\"model\":\"m\"},"
                                        + "{\"id\":\"Bad_Underscore\",\"name\":\"id 非法\",\"protocol\":\"openai-chat\",\"baseUrl\":\"https://x\",\"model\":\"m\"},"
                                        + "{\"id\":\"ok\",\"name\":\"合法\",\"protocol\":\"openai-responses\",\"baseUrl\":\"https://x\",\"model\":\"m\"}]}"));

        LlmProviderCatalog.Catalog snapshot = catalog.read();

        assertThat(snapshot.providers())
                .hasSize(1)
                .first()
                .satisfies(
                        descriptor -> {
                            assertThat(descriptor.id()).isEqualTo("ok");
                            assertThat(descriptor.protocol())
                                    .isEqualTo(LlmProtocol.OPENAI_RESPONSES);
                        });
    }

    /** [AC-2] 激活键损坏：按未激活处理，目录条目不受影响。 */
    @Test
    void failsClosedOnCorruptActiveKey_ac2() {
        when(appSettingService.findValue(LlmProviderCatalog.PROVIDERS_KEY))
                .thenReturn(
                        Optional.of(
                                "{\"schemaVersion\":1,\"providers\":[{\"id\":\"zai\",\"name\":\"n\","
                                        + "\"protocol\":\"anthropic-messages\",\"baseUrl\":\"https://x\",\"model\":\"m\"}]}"));
        when(appSettingService.findValue(LlmProviderCatalog.ACTIVE_KEY))
                .thenReturn(Optional.of("broken"));

        LlmProviderCatalog.Catalog snapshot = catalog.read();

        assertThat(snapshot.providers()).hasSize(1);
        assertThat(snapshot.activeId()).isNull();
    }

    /** [AC-2] 写入形状：schemaVersion 携带、protocol 落 wire 值；激活写入独立键。 */
    @Test
    void serializesSchemaVersionAndWireProtocol_ac2() {
        catalog.saveProviders(
                List.of(
                        new LlmProviderDescriptor(
                                "zai",
                                "ZCode 网关",
                                LlmProtocol.ANTHROPIC_MESSAGES,
                                "http://127.0.0.1:47860/gateway/zcode",
                                "glm-5.3")),
                1L);
        catalog.saveActive("zai", 1L);

        ArgumentCaptor<String> providersValue = ArgumentCaptor.forClass(String.class);
        verify(appSettingService)
                .upsert(
                        eq(LlmProviderCatalog.PROVIDERS_KEY),
                        providersValue.capture(),
                        eq("LLM provider 目录（022，密钥走 env 索引不入库）"),
                        eq(1L));
        assertThat(providersValue.getValue())
                .contains("\"schemaVersion\":1")
                .contains("\"protocol\":\"anthropic-messages\"")
                .contains("\"id\":\"zai\"")
                .doesNotContain("apiKey");

        ArgumentCaptor<String> activeValue = ArgumentCaptor.forClass(String.class);
        verify(appSettingService)
                .upsert(
                        eq(LlmProviderCatalog.ACTIVE_KEY),
                        activeValue.capture(),
                        eq("激活的 LLM provider（022）"),
                        eq(1L));
        assertThat(activeValue.getValue())
                .isEqualTo("{\"schemaVersion\":1,\"providerId\":\"zai\"}");
    }

    /** [AC-2] env 索引口径：YILIAN_LLM_KEY_&lt;大写ID&gt;，连字符→下划线（zai→ZAI；zcode-gateway→ZCODE_GATEWAY）。 */
    @Test
    void resolvesEnvKeyByUppercasedId_ac2() {
        when(environment.getProperty("YILIAN_LLM_KEY_ZAI", "")).thenReturn("sk-zai");
        when(environment.getProperty("YILIAN_LLM_KEY_ZCODE_GATEWAY", "")).thenReturn("");

        assertThat(catalog.envKey("zai")).isEqualTo("sk-zai");
        assertThat(catalog.envKey("zcode-gateway")).isEmpty();
        assertThat(catalog.envKey("")).isEmpty();
    }
}

package io.github.codejinxz.yilian.practice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.practice.llm.LlmProtocol;
import io.github.codejinxz.yilian.practice.llm.LlmProviderCatalog;
import io.github.codejinxz.yilian.practice.llm.LlmProviderDescriptor;
import io.github.codejinxz.yilian.practice.llm.LlmProviderKeys;
import io.github.codejinxz.yilian.practice.service.dto.LlmProviderCreateRequest;
import io.github.codejinxz.yilian.practice.service.dto.LlmProviderUpdateRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

/**
 * {@link LlmProviderAdminServiceImpl} 单测 [AC-2]/[AC-4]：目录 CRUD 与激活切换的服务端规则——id 形态/唯一性、
 * 协议白名单、激活中不可删、激活唯一与幂等；027B 密钥口径——写-only apiKey（非空白=加密 upsert、 空/缺省=不变更）、hasKey=解析链口径（DB 或 env
 * 任一）、keyLast4 只在 DB 态给值、删除同事务删密钥行。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LlmProviderAdminServiceImplTest {

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

    @Mock private LlmProviderCatalog catalog;

    @Mock private LlmProviderKeys providerKeys;

    private LlmProviderAdminServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LlmProviderAdminServiceImpl(catalog, providerKeys);
        when(catalog.read()).thenReturn(new LlmProviderCatalog.Catalog(List.of(), null));
        // 缺省密钥状态：zai 有 env 态密钥（无 DB 行）、zhipu 无任何密钥（解析链 mock 口径）。
        when(providerKeys.hasKey("zai")).thenReturn(true);
        when(providerKeys.storedLast4("zai")).thenReturn(Optional.empty());
        when(providerKeys.hasKey("zhipu")).thenReturn(false);
        when(providerKeys.storedLast4("zhipu")).thenReturn(Optional.empty());
    }

    /** [AC-4] list：条目映射 hasKey（解析链口径）与 keyLast4（仅 DB 态给值，env 态 null）。 */
    @Test
    @DisplayName("[AC-4] list 映射 hasKey 新口径与 keyLast4")
    void listMapsHasKeyAndLast4_ac4() {
        when(catalog.read()).thenReturn(new LlmProviderCatalog.Catalog(List.of(ZAI, ZHIPU), "zai"));
        // zai 改为「DB 态」：有 last4；zhipu 保持无密钥。
        when(providerKeys.storedLast4("zai")).thenReturn(Optional.of("skai"));

        List<io.github.codejinxz.yilian.practice.service.vo.LlmProviderView> views = service.list();

        assertThat(views).hasSize(2);
        assertThat(views.get(0))
                .satisfies(
                        view -> {
                            assertThat(view.id()).isEqualTo("zai");
                            assertThat(view.protocol()).isEqualTo("anthropic-messages");
                            assertThat(view.hasKey()).isTrue();
                            assertThat(view.keyLast4()).isEqualTo("skai");
                            assertThat(view.active()).isTrue();
                        });
        assertThat(views.get(1))
                .satisfies(
                        view -> {
                            assertThat(view.hasKey()).isFalse();
                            assertThat(view.keyLast4()).isNull();
                            assertThat(view.active()).isFalse();
                        });
    }

    /** [AC-4] hasKey 口径 =「DB 或 env 任一」：仅 env（无 DB 行）时 hasKey=true 且 last4 为 null。 */
    @Test
    @DisplayName("[AC-4] 仅 env 密钥时 hasKey=true 且 last4=null")
    void envOnlyKeyStillHasKeyWithoutLast4_ac4() {
        when(catalog.read()).thenReturn(new LlmProviderCatalog.Catalog(List.of(ZAI), "zai"));

        var view = service.list().get(0);

        assertThat(view.hasKey()).isTrue();
        assertThat(view.keyLast4()).isNull();
        verify(providerKeys, never()).upsert(anyString(), anyString());
    }

    /** [AC-2] create：追加目录末尾（默认未激活），id 唯一，协议经 wire 白名单校验。 */
    @Test
    void createAppendsProviderWithValidatedFields_ac2() {
        LlmProviderCreateRequest request =
                new LlmProviderCreateRequest(
                        "deep-seek",
                        "DeepSeek",
                        "openai-chat",
                        "https://api.deepseek.com",
                        "deepseek-chat",
                        null);

        var view = service.create(request, 1L);

        assertThat(view.id()).isEqualTo("deep-seek");
        assertThat(view.active()).isFalse();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmProviderDescriptor>> captor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(catalog).saveProviders(captor.capture(), eq(1L));
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).protocol()).isEqualTo(LlmProtocol.OPENAI_CHAT);
    }

    /** [AC-2] create：id 重复 40000；id 形态非法 40000（服务端兜底，不依赖 Bean Validation）；协议未知 40000。 */
    @Test
    void createRejectsDuplicateIdBadIdShapeAndUnknownProtocol_ac2() {
        when(catalog.read()).thenReturn(new LlmProviderCatalog.Catalog(List.of(ZAI), null));

        assertThatThrownBy(
                        () ->
                                service.create(
                                        new LlmProviderCreateRequest(
                                                "zai", "重复", "openai-chat", "https://x", "m", null),
                                        1L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        verify(catalog, never()).saveProviders(anyList(), eq(1L));

        assertThatThrownBy(
                        () ->
                                service.create(
                                        new LlmProviderCreateRequest(
                                                "Bad_Id",
                                                "形态非法",
                                                "openai-chat",
                                                "https://x",
                                                "m",
                                                null),
                                        1L))
                .isInstanceOf(BusinessException.class);

        assertThatThrownBy(
                        () ->
                                service.create(
                                        new LlmProviderCreateRequest(
                                                "ok", "协议未知", "gemini", "https://x", "m", null),
                                        1L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getMessage()).contains("protocol 只允许"));
    }

    /** [AC-4] create/update 的写-only apiKey：非空白=加密 upsert（明文透传给密钥链）；空/缺省=不变更不触碰密钥。 */
    @Test
    @DisplayName("[AC-4] 写-only apiKey 非空白 upsert、空白不变更")
    void apiKeyWriteOnlySemantics_ac4() {
        // create 带非空白 apiKey：透传明文给密钥链（加密在链内，明文不落目录/日志）。
        service.create(
                new LlmProviderCreateRequest(
                        "new-prov",
                        "新条目",
                        "openai-chat",
                        "https://x",
                        "m",
                        "  sk-ui-plaintext-001  "),
                1L);
        verify(providerKeys).upsert("new-prov", "sk-ui-plaintext-001");

        // create 不带 apiKey：不触碰密钥链。
        service.create(
                new LlmProviderCreateRequest(
                        "new-prov-2", "新条目二", "openai-chat", "https://x", "m", null),
                1L);
        verify(providerKeys, never()).upsert(eq("new-prov-2"), anyString());

        // update 空串/缺省=不变更；非空白=覆盖。
        when(catalog.read()).thenReturn(new LlmProviderCatalog.Catalog(List.of(ZHIPU), null));
        service.update(
                "zhipu",
                new LlmProviderUpdateRequest(
                        "智谱",
                        "openai-chat",
                        "https://open.bigmodel.cn/api/paas/v4",
                        "glm-4.7",
                        null),
                1L);
        verify(providerKeys, never()).upsert(eq("zhipu"), anyString());
        service.update(
                "zhipu",
                new LlmProviderUpdateRequest(
                        "智谱",
                        "openai-chat",
                        "https://open.bigmodel.cn/api/paas/v4",
                        "glm-4.7",
                        "sk-rotated-key-002"),
                1L);
        verify(providerKeys).upsert("zhipu", "sk-rotated-key-002");

        // 空白串等价缺省（trim 后不 upsert）。
        service.update(
                "zhipu",
                new LlmProviderUpdateRequest(
                        "智谱",
                        "openai-chat",
                        "https://open.bigmodel.cn/api/paas/v4",
                        "glm-4.7",
                        "   "),
                1L);
        verify(providerKeys, never()).upsert(eq("zhipu"), eq("   "));
    }

    /** [AC-4] delete：目录条目删除同事务删密钥行（无论条目此前是否有入库密钥，调用幂等）。 */
    @Test
    @DisplayName("[AC-4] 删除条目同事务删密钥行")
    void deleteRemovesKeyRowInSameTransaction_ac4() {
        when(catalog.read()).thenReturn(new LlmProviderCatalog.Catalog(List.of(ZAI, ZHIPU), "zai"));

        service.delete("zhipu", 1L);

        verify(providerKeys).delete("zhipu");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmProviderDescriptor>> captor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(catalog).saveProviders(captor.capture(), eq(1L));
        assertThat(captor.getValue()).extracting(LlmProviderDescriptor::id).containsExactly("zai");
    }

    /** [AC-2] update：整体覆盖元数据（id 不变）；目标不存在 40400。 */
    @Test
    void updateOverwritesMetadataAndRejectsUnknownId_ac2() {
        when(catalog.read()).thenReturn(new LlmProviderCatalog.Catalog(List.of(ZAI), "zai"));

        var view =
                service.update(
                        "zai",
                        new LlmProviderUpdateRequest(
                                "ZCode 网关（改）",
                                "anthropic-messages",
                                "http://10.150.191.220:47860/gateway/zcode",
                                "glm-5.3",
                                null),
                        1L);

        assertThat(view.name()).isEqualTo("ZCode 网关（改）");
        assertThat(view.baseUrl()).isEqualTo("http://10.150.191.220:47860/gateway/zcode");
        assertThat(view.active()).isTrue();

        assertThatThrownBy(
                        () ->
                                service.update(
                                        "ghost",
                                        new LlmProviderUpdateRequest(
                                                "n", "openai-chat", "https://x", "m", null),
                                        1L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.apiCode()).isEqualTo(ApiCode.NOT_FOUND));
    }

    /** [AC-2] delete：非激活条目可删（目录收敛）；激活中 40000 拒绝；不存在 40400（密钥行不动）。 */
    @Test
    void deleteEnforcesActiveProtection_ac2() {
        when(catalog.read()).thenReturn(new LlmProviderCatalog.Catalog(List.of(ZAI, ZHIPU), "zai"));

        assertThatThrownBy(() -> service.delete("zai", 1L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> {
                            assertThat(exception.apiCode()).isEqualTo(ApiCode.VALIDATION_FAILED);
                            assertThat(exception.getMessage()).contains("激活中的 provider 不能删除");
                        });
        verify(providerKeys, never()).delete("zai");

        assertThatThrownBy(() -> service.delete("ghost", 1L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.apiCode()).isEqualTo(ApiCode.NOT_FOUND));
        verify(providerKeys, never()).delete("ghost");
    }

    /** [AC-2] activate：写激活键（激活唯一）；重复激活幂等不重写；不存在 40400。 */
    @Test
    void activatePersistsUniqueActiveId_ac2() {
        when(catalog.read()).thenReturn(new LlmProviderCatalog.Catalog(List.of(ZAI, ZHIPU), null));

        var view = service.activate("zai", 1L);

        assertThat(view.active()).isTrue();
        verify(catalog).saveActive("zai", 1L);

        // 已激活再激活：幂等，不重复写。
        when(catalog.read()).thenReturn(new LlmProviderCatalog.Catalog(List.of(ZAI, ZHIPU), "zai"));
        service.activate("zai", 1L);
        verify(catalog).saveActive("zai", 1L); // 仍只有第一次的写入

        assertThatThrownBy(() -> service.activate("ghost", 1L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.apiCode()).isEqualTo(ApiCode.NOT_FOUND));
        verify(catalog, never()).saveActive(eq("ghost"), eq(1L));
    }
}

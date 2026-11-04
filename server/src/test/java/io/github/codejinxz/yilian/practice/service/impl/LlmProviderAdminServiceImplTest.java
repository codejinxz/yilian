package io.github.codejinxz.yilian.practice.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.practice.llm.LlmProtocol;
import io.github.codejinxz.yilian.practice.llm.LlmProviderCatalog;
import io.github.codejinxz.yilian.practice.llm.LlmProviderDescriptor;
import io.github.codejinxz.yilian.practice.service.dto.LlmProviderCreateRequest;
import io.github.codejinxz.yilian.practice.service.dto.LlmProviderUpdateRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

/**
 * {@link LlmProviderAdminServiceImpl} 单测 [AC-2]：目录 CRUD 与激活切换的服务端规则——id 形态/唯一性、协议白名单、
 * 激活中不可删、激活唯一与幂等、hasKey 走 env 索引（不落库）。
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

    private LlmProviderAdminServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LlmProviderAdminServiceImpl(catalog);
        when(catalog.read()).thenReturn(new LlmProviderCatalog.Catalog(List.of(), null));
        when(catalog.envKey("zai")).thenReturn("sk-zai");
        when(catalog.envKey("zhipu")).thenReturn("");
    }

    /** [AC-2] list：条目映射含 hasKey（env 实时索引）与激活态。 */
    @Test
    void listMapsHasKeyAndActiveFlag_ac2() {
        when(catalog.read()).thenReturn(new LlmProviderCatalog.Catalog(List.of(ZAI, ZHIPU), "zai"));

        List<io.github.codejinxz.yilian.practice.service.vo.LlmProviderView> views = service.list();

        assertThat(views).hasSize(2);
        assertThat(views.get(0))
                .satisfies(
                        view -> {
                            assertThat(view.id()).isEqualTo("zai");
                            assertThat(view.protocol()).isEqualTo("anthropic-messages");
                            assertThat(view.hasKey()).isTrue();
                            assertThat(view.active()).isTrue();
                        });
        assertThat(views.get(1))
                .satisfies(
                        view -> {
                            assertThat(view.hasKey()).isFalse();
                            assertThat(view.active()).isFalse();
                        });
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
                        "deepseek-chat");

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
                                                "zai", "重复", "openai-chat", "https://x", "m"),
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
                                                "Bad_Id", "形态非法", "openai-chat", "https://x", "m"),
                                        1L))
                .isInstanceOf(BusinessException.class);

        assertThatThrownBy(
                        () ->
                                service.create(
                                        new LlmProviderCreateRequest(
                                                "ok", "协议未知", "gemini", "https://x", "m"),
                                        1L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.getMessage()).contains("protocol 只允许"));
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
                                "glm-5.3"),
                        1L);

        assertThat(view.name()).isEqualTo("ZCode 网关（改）");
        assertThat(view.baseUrl()).isEqualTo("http://10.150.191.220:47860/gateway/zcode");
        assertThat(view.active()).isTrue();

        assertThatThrownBy(
                        () ->
                                service.update(
                                        "ghost",
                                        new LlmProviderUpdateRequest(
                                                "n", "openai-chat", "https://x", "m"),
                                        1L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.apiCode()).isEqualTo(ApiCode.NOT_FOUND));
    }

    /** [AC-2] delete：非激活条目可删（目录收敛）；激活中 40000 拒绝；不存在 40400。 */
    @Test
    void deleteEnforcesActiveProtection_ac2() {
        when(catalog.read()).thenReturn(new LlmProviderCatalog.Catalog(List.of(ZAI, ZHIPU), "zai"));

        service.delete("zhipu", 1L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmProviderDescriptor>> captor =
                ArgumentCaptor.forClass((Class) List.class);
        verify(catalog).saveProviders(captor.capture(), eq(1L));
        assertThat(captor.getValue()).extracting(LlmProviderDescriptor::id).containsExactly("zai");

        assertThatThrownBy(() -> service.delete("zai", 1L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> {
                            assertThat(exception.apiCode()).isEqualTo(ApiCode.VALIDATION_FAILED);
                            assertThat(exception.getMessage()).contains("激活中的 provider 不能删除");
                        });

        assertThatThrownBy(() -> service.delete("ghost", 1L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.apiCode()).isEqualTo(ApiCode.NOT_FOUND));
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

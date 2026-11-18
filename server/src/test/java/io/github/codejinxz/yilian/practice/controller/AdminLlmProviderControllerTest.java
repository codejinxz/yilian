package io.github.codejinxz.yilian.practice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.logging.ErrorLogRecorder;
import io.github.codejinxz.yilian.common.logging.OperationLog;
import io.github.codejinxz.yilian.common.logging.OperationLogContext;
import io.github.codejinxz.yilian.common.security.PasswordChangeRequiredInterceptor;
import io.github.codejinxz.yilian.common.security.RestAccessDeniedHandler;
import io.github.codejinxz.yilian.common.security.RestAuthenticationEntryPoint;
import io.github.codejinxz.yilian.common.security.SecurityConfig;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.practice.service.LlmProviderAdminService;
import io.github.codejinxz.yilian.practice.service.dto.LlmProviderCreateRequest;
import io.github.codejinxz.yilian.practice.service.dto.LlmProviderUpdateRequest;
import io.github.codejinxz.yilian.practice.service.vo.LlmProviderView;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

/**
 * 管理端 provider 目录端点（API §8.15）的 HTTP 层切片测试 [AC-2]：鉴权矩阵（匿名 401 / 非管理员 403）、GET 形状 （hasKey/active、无
 * apiKey 字段）、POST/PUT/DELETE/activate 透传与审计注解口径（四个写操作标 LLM_CONFIG_UPDATE、 读不标）、POST id 形态 Bean
 * Validation；服务层分支由 {@code LlmProviderAdminServiceImplTest} 覆盖。
 */
@WebMvcTest(controllers = AdminLlmProviderController.class)
@Import({
    SecurityConfig.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    PasswordChangeRequiredInterceptor.class
})
class AdminLlmProviderControllerTest {

    private static final YilianUserPrincipal ADMIN =
            YilianUserPrincipal.of(1L, "admin", "ADMIN", true);

    private static final YilianUserPrincipal USER =
            YilianUserPrincipal.of(7L, "learner", "USER", true);

    @Autowired private MockMvc mockMvc;

    @MockitoBean private LlmProviderAdminService providerAdminService;

    @MockitoBean private JwtDecoder jwtDecoder;

    @MockitoBean private ErrorLogRecorder errorLogRecorder;

    @AfterEach
    void cleanOperationLogContext() {
        // 切面不在 @WebMvcTest 上下文里，测试线程上的审计覆盖值需要手动清，避免串测。
        OperationLogContext.clear();
    }

    /** [AC-2] 匿名访问：GET/POST/DELETE 全部 401 信封（code=40100），不触达 Service。 */
    @Test
    void providerEndpointsRejectAnonymousWith401_ac2() throws Exception {
        mockMvc.perform(get("/api/admin/llm/providers"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        mockMvc.perform(
                        post("/api/admin/llm/providers")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        mockMvc.perform(delete("/api/admin/llm/providers/zai").with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        verifyNoInteractions(providerAdminService);
    }

    /** [AC-2] 非 ADMIN 登录用户：403 信封（code=40300），权限在服务端。 */
    @Test
    void providerEndpointsRejectNonAdminWith403_ac2() throws Exception {
        mockMvc.perform(
                        get("/api/admin/llm/providers")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));
        mockMvc.perform(
                        put("/api/admin/llm/providers/zai/activate")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));
        verifyNoInteractions(providerAdminService);
    }

    /**
     * [AC-2] GET：data 为 {items:[...]} 列表信封（裸数组会让前端模型页整页不可用）；条目形状——元数据 + hasKey + active，无 apiKey
     * 字段回显。
     */
    @Test
    void listReturnsItemsEnvelopeWithHasKeyAndActive_ac2() throws Exception {
        when(providerAdminService.list())
                .thenReturn(
                        List.of(
                                new LlmProviderView(
                                        "zai",
                                        "ZCode 网关",
                                        "anthropic-messages",
                                        "http://127.0.0.1:47860/gateway/zcode",
                                        "glm-5.3",
                                        false,
                                        null,
                                        true)));

        mockMvc.perform(
                        get("/api/admin/llm/providers")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value("zai"))
                .andExpect(jsonPath("$.data.items[0].name").value("ZCode 网关"))
                .andExpect(jsonPath("$.data.items[0].protocol").value("anthropic-messages"))
                .andExpect(
                        jsonPath("$.data.items[0].baseUrl")
                                .value("http://127.0.0.1:47860/gateway/zcode"))
                .andExpect(jsonPath("$.data.items[0].model").value("glm-5.3"))
                .andExpect(jsonPath("$.data.items[0].hasKey").value(false))
                .andExpect(jsonPath("$.data.items[0].active").value(true))
                .andExpect(jsonPath("$.data.items[0].apiKey").doesNotExist());
    }

    /** [AC-2] GET：空目录——信封仍为 {items:[]}，不退回裸数组。 */
    @Test
    void listReturnsEmptyItemsArrayForEmptyDirectory_ac2() throws Exception {
        when(providerAdminService.list()).thenReturn(List.of());

        mockMvc.perform(
                        get("/api/admin/llm/providers")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    /** [AC-2] POST：合法请求透传 Service（id/name/protocol/baseUrl/model + 操作者），返回条目视图。 */
    @Test
    void createPersistsThroughService_ac2() throws Exception {
        when(providerAdminService.create(
                        new LlmProviderCreateRequest(
                                "zai",
                                "ZCode 网关",
                                "anthropic-messages",
                                "http://127.0.0.1:47860/gateway/zcode",
                                "glm-5.3",
                                null),
                        1L))
                .thenReturn(
                        new LlmProviderView(
                                "zai",
                                "ZCode 网关",
                                "anthropic-messages",
                                "http://127.0.0.1:47860/gateway/zcode",
                                "glm-5.3",
                                false,
                                null,
                                false));

        mockMvc.perform(
                        post("/api/admin/llm/providers")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"id\":\"zai\",\"name\":\"ZCode 网关\","
                                                + "\"protocol\":\"anthropic-messages\","
                                                + "\"baseUrl\":\"http://127.0.0.1:47860/gateway/zcode\","
                                                + "\"model\":\"glm-5.3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value("zai"))
                .andExpect(jsonPath("$.data.active").value(false));
    }

    /** [AC-2] POST：id 形态非法（大写/下划线/空）走 Bean Validation 40000；Service 的 id 重复 40000 以信封透出。 */
    @Test
    void createValidationAndDuplicateIdRejections_ac2() throws Exception {
        mockMvc.perform(
                        post("/api/admin/llm/providers")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"id\":\"Bad_Id\",\"name\":\"n\",\"protocol\":\"openai-chat\","
                                                + "\"baseUrl\":\"https://x\",\"model\":\"m\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));

        when(providerAdminService.create(any(), eq(1L)))
                .thenThrow(new BusinessException(ApiCode.VALIDATION_FAILED, "provider id 已存在：zai"));
        mockMvc.perform(
                        post("/api/admin/llm/providers")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"id\":\"zai\",\"name\":\"n\",\"protocol\":\"openai-chat\","
                                                + "\"baseUrl\":\"https://x\",\"model\":\"m\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()))
                .andExpect(jsonPath("$.message").value("provider id 已存在：zai"));
    }

    /** [AC-2] PUT /{id}：更新透传；DELETE /{id}：删除透传（激活中 40000 由 Service 抛、信封透出）。 */
    @Test
    void updateAndDeletePassThroughService_ac2() throws Exception {
        when(providerAdminService.update(
                        eq("zai"),
                        eq(
                                new LlmProviderUpdateRequest(
                                        "ZCode 网关",
                                        "anthropic-messages",
                                        "https://x",
                                        "glm-5.3",
                                        null)),
                        eq(1L)))
                .thenReturn(
                        new LlmProviderView(
                                "zai",
                                "ZCode 网关",
                                "anthropic-messages",
                                "https://x",
                                "glm-5.3",
                                false,
                                null,
                                true));

        mockMvc.perform(
                        put("/api/admin/llm/providers/zai")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"name\":\"ZCode 网关\",\"protocol\":\"anthropic-messages\","
                                                + "\"baseUrl\":\"https://x\",\"model\":\"glm-5.3\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("zai"));

        mockMvc.perform(
                        delete("/api/admin/llm/providers/zai")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(providerAdminService).delete("zai", 1L);
    }

    /** [AC-2] PUT /{id}/activate：激活透传；Service 40400（provider 不存在）以信封透出。 */
    @Test
    void activatePassesThroughAndNotFoundEnvelopes_ac2() throws Exception {
        when(providerAdminService.activate(eq("zai"), eq(1L)))
                .thenReturn(
                        new LlmProviderView(
                                "zai", "n", "openai-chat", "https://x", "m", true, null, true));

        mockMvc.perform(
                        put("/api/admin/llm/providers/zai/activate")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(true));

        when(providerAdminService.activate(eq("ghost"), eq(1L)))
                .thenThrow(new BusinessException(ApiCode.NOT_FOUND, "provider 不存在：ghost"));
        mockMvc.perform(
                        put("/api/admin/llm/providers/ghost/activate")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiCode.NOT_FOUND.code()))
                .andExpect(jsonPath("$.message").value("provider 不存在：ghost"));
    }

    /** [AC-2] 审计口径：四个写操作标 @OperationLog(LLM_CONFIG_UPDATE)，GET 不标。 */
    @Test
    void writesCarryAuditAndReadDoesNot_ac2() throws Exception {
        for (String method : new String[] {"create", "update", "delete", "activate"}) {
            OperationLog annotation =
                    switch (method) {
                        case "create" ->
                                AdminLlmProviderController.class
                                        .getDeclaredMethod(
                                                "create",
                                                LlmProviderCreateRequest.class,
                                                YilianUserPrincipal.class)
                                        .getAnnotation(OperationLog.class);
                        case "update" ->
                                AdminLlmProviderController.class
                                        .getDeclaredMethod(
                                                "update",
                                                String.class,
                                                LlmProviderUpdateRequest.class,
                                                YilianUserPrincipal.class)
                                        .getAnnotation(OperationLog.class);
                        case "delete" ->
                                AdminLlmProviderController.class
                                        .getDeclaredMethod(
                                                "delete", String.class, YilianUserPrincipal.class)
                                        .getAnnotation(OperationLog.class);
                        default ->
                                AdminLlmProviderController.class
                                        .getDeclaredMethod(
                                                "activate", String.class, YilianUserPrincipal.class)
                                        .getAnnotation(OperationLog.class);
                    };
            assertThat(annotation).as("写操作 %s 必须带审计注解", method).isNotNull();
            assertThat(annotation.module()).isEqualTo("llm");
            assertThat(annotation.action()).isEqualTo("LLM_CONFIG_UPDATE");
        }

        OperationLog list =
                AdminLlmProviderController.class
                        .getDeclaredMethod("list", YilianUserPrincipal.class)
                        .getAnnotation(OperationLog.class);
        assertThat(list).isNull();
    }

    /** [AC-4] POST/PUT 的写-only apiKey：请求体明文透传 Service（加密在服务层），响应永不回显明文。 */
    @Test
    void apiKeyWriteOnlyPassthroughWithoutEcho_ac4() throws Exception {
        when(providerAdminService.create(
                        eq(
                                new LlmProviderCreateRequest(
                                        "zai",
                                        "ZCode 网关",
                                        "anthropic-messages",
                                        "http://127.0.0.1:47860/gateway/zcode",
                                        "glm-5.3",
                                        "sk-plaintext-from-ui-001")),
                        eq(1L)))
                .thenReturn(
                        new LlmProviderView(
                                "zai",
                                "ZCode 网关",
                                "anthropic-messages",
                                "http://127.0.0.1:47860/gateway/zcode",
                                "glm-5.3",
                                true,
                                "001",
                                false));

        String createResponse =
                mockMvc.perform(
                                post("/api/admin/llm/providers")
                                        .with(
                                                authentication(
                                                        new TestingAuthenticationToken(
                                                                ADMIN, null, "ROLE_ADMIN")))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"id\":\"zai\",\"name\":\"ZCode 网关\","
                                                        + "\"protocol\":\"anthropic-messages\","
                                                        + "\"baseUrl\":\"http://127.0.0.1:47860/gateway/zcode\","
                                                        + "\"model\":\"glm-5.3\","
                                                        + "\"apiKey\":\"sk-plaintext-from-ui-001\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.hasKey").value(true))
                        .andExpect(jsonPath("$.data.keyLast4").value("001"))
                        // 明文与 apiKey 字段均不出现在响应（写-only：请求收、响应永不回）。
                        .andExpect(jsonPath("$.data.apiKey").doesNotExist())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(createResponse).doesNotContain("sk-plaintext-from-ui-001");

        when(providerAdminService.update(
                        eq("zai"),
                        eq(
                                new LlmProviderUpdateRequest(
                                        "ZCode 网关",
                                        "anthropic-messages",
                                        "https://x",
                                        "glm-5.3",
                                        "sk-rotated-002")),
                        eq(1L)))
                .thenReturn(
                        new LlmProviderView(
                                "zai",
                                "ZCode 网关",
                                "anthropic-messages",
                                "https://x",
                                "glm-5.3",
                                true,
                                "002",
                                true));

        String updateResponse =
                mockMvc.perform(
                                put("/api/admin/llm/providers/zai")
                                        .with(
                                                authentication(
                                                        new TestingAuthenticationToken(
                                                                ADMIN, null, "ROLE_ADMIN")))
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"name\":\"ZCode 网关\",\"protocol\":\"anthropic-messages\","
                                                        + "\"baseUrl\":\"https://x\",\"model\":\"glm-5.3\","
                                                        + "\"apiKey\":\"sk-rotated-002\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.keyLast4").value("002"))
                        .andExpect(jsonPath("$.data.apiKey").doesNotExist())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(updateResponse).doesNotContain("sk-rotated-002");
    }

    /** [AC-4] GET 条目形状：keyLast4 只在入库态有值，env 态（null）不显示 last4；响应无任何明文字段。 */
    @Test
    void listExposesKeyLast4OnlyForStoredKeys_ac4() throws Exception {
        when(providerAdminService.list())
                .thenReturn(
                        List.of(
                                new LlmProviderView(
                                        "stored",
                                        "入库密钥条目",
                                        "openai-chat",
                                        "https://x",
                                        "m",
                                        true,
                                        "8811",
                                        false),
                                new LlmProviderView(
                                        "env-only",
                                        "env 密钥条目",
                                        "openai-chat",
                                        "https://y",
                                        "m",
                                        true,
                                        null,
                                        true)));

        String body =
                mockMvc.perform(
                                get("/api/admin/llm/providers")
                                        .with(
                                                authentication(
                                                        new TestingAuthenticationToken(
                                                                ADMIN, null, "ROLE_ADMIN"))))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.items[0].keyLast4").value("8811"))
                        .andExpect(jsonPath("$.data.items[1].keyLast4").doesNotExist())
                        .andExpect(jsonPath("$.data.items[0].apiKey").doesNotExist())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("apiKey");
    }
}

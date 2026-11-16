package io.github.codejinxz.yilian.practice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import io.github.codejinxz.yilian.practice.service.TutorService;
import io.github.codejinxz.yilian.practice.service.dto.LlmConfigUpdateRequest;
import io.github.codejinxz.yilian.practice.service.vo.LlmConfigView;

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

/**
 * 管理端 LLM 配置端点（API §8.14）的 HTTP 层切片测试 [AC-1]：鉴权矩阵（匿名 401 / 非管理员 403）、 GET 形状（hasKey/keyLast4 投影、无
 * apiKey 回显）、PUT 透传（覆盖/密钥/清除全字段）与 40000 信封、审计注解口径（写 LLM_CONFIG_UPDATE、读不标）； 服务层分支由 {@code
 * TutorServiceImplTest} 覆盖。
 */
@WebMvcTest(controllers = AdminLlmConfigController.class)
@Import({
    SecurityConfig.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    PasswordChangeRequiredInterceptor.class
})
class AdminLlmConfigControllerTest {

    private static final YilianUserPrincipal ADMIN =
            YilianUserPrincipal.of(1L, "admin", "ADMIN", true);

    private static final YilianUserPrincipal USER =
            YilianUserPrincipal.of(7L, "learner", "USER", true);

    @Autowired private MockMvc mockMvc;

    @MockitoBean private TutorService tutorService;

    @MockitoBean private JwtDecoder jwtDecoder;

    @MockitoBean private ErrorLogRecorder errorLogRecorder;

    @AfterEach
    void cleanOperationLogContext() {
        // 切面不在 @WebMvcTest 上下文里，测试线程上的审计覆盖值需要手动清，避免串测。
        OperationLogContext.clear();
    }

    /** [AC-1] 匿名访问：GET/PUT 都是 401 信封（code=40100），不触达 Service。 */
    @Test
    void configEndpointsRejectAnonymousWith401_ac1() throws Exception {
        mockMvc.perform(get("/api/admin/llm/config"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        mockMvc.perform(
                        put("/api/admin/llm/config")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"enabled\":true}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        verifyNoInteractions(tutorService);
    }

    /** [AC-1] 非 ADMIN 登录用户：403 信封（code=40300），权限在服务端。 */
    @Test
    void configEndpointsRejectNonAdminWith403_ac1() throws Exception {
        mockMvc.perform(
                        get("/api/admin/llm/config")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));
        mockMvc.perform(
                        put("/api/admin/llm/config")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"enabled\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));
        verifyNoInteractions(tutorService);
    }

    /**
     * [AC-1] GET：200 信封 data 为 {enabled, baseUrl, model, hasKey, providerId, protocol,
     * credentialsComplete, source, keyLast4}，无 apiKey 字段回显（写-only 红线）。
     */
    @Test
    void getConfigReturnsProjectionWithoutSecret_ac1() throws Exception {
        when(tutorService.getAdminConfig())
                .thenReturn(
                        new LlmConfigView(
                                true,
                                "http://10.0.0.8:8080/v4",
                                "glm-5.3-flash",
                                true,
                                "legacy",
                                "openai-chat",
                                true,
                                "custom",
                                "8811"));

        mockMvc.perform(
                        get("/api/admin/llm/config")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.baseUrl").value("http://10.0.0.8:8080/v4"))
                .andExpect(jsonPath("$.data.model").value("glm-5.3-flash"))
                .andExpect(jsonPath("$.data.hasKey").value(true))
                .andExpect(jsonPath("$.data.source").value("custom"))
                .andExpect(jsonPath("$.data.keyLast4").value("8811"))
                .andExpect(jsonPath("$.data.apiKey").doesNotExist());
    }

    /** [AC-1] PUT：覆盖 + 密钥 + 清除全字段透传 Service（请求体整形状），返回更新后的视图。 */
    @Test
    void putConfigPassesThroughFullBody_ac1() throws Exception {
        when(tutorService.updateAdminConfig(
                        new LlmConfigUpdateRequest(
                                true, "http://10.0.0.8:8080/v4", "glm-5.3-flash", "sk-x", null),
                        1L))
                .thenReturn(
                        new LlmConfigView(
                                true,
                                "http://10.0.0.8:8080/v4",
                                "glm-5.3-flash",
                                true,
                                "legacy",
                                "openai-chat",
                                true,
                                "custom",
                                "sk-x"));

        mockMvc.perform(
                        put("/api/admin/llm/config")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"enabled\":true,\"baseUrl\":\"http://10.0.0.8:8080/v4\","
                                                + "\"model\":\"glm-5.3-flash\",\"apiKey\":\"sk-x\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.source").value("custom"));
        verify(tutorService)
                .updateAdminConfig(
                        new LlmConfigUpdateRequest(
                                true, "http://10.0.0.8:8080/v4", "glm-5.3-flash", "sk-x", null),
                        1L);
    }

    /** [AC-1] PUT：clearKey=true 透传（删 tutor 密钥行语义由 Service 层锁定）。 */
    @Test
    void putConfigPassesClearKeyFlag_ac1() throws Exception {
        when(tutorService.updateAdminConfig(
                        new LlmConfigUpdateRequest(false, null, null, null, true), 1L))
                .thenReturn(
                        new LlmConfigView(false, "", "", false, null, null, false, "env", null));

        mockMvc.perform(
                        put("/api/admin/llm/config")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"enabled\":false,\"clearKey\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        verify(tutorService)
                .updateAdminConfig(new LlmConfigUpdateRequest(false, null, null, null, true), 1L);
    }

    /** [AC-1] PUT：enabled 缺失（40000）与 Service 校验/拒绝（40000）都以信封返回。 */
    @Test
    void putConfigValidationAndServiceRejections_ac1() throws Exception {
        mockMvc.perform(
                        put("/api/admin/llm/config")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));

        when(tutorService.updateAdminConfig(any(), eq(1L)))
                .thenThrow(
                        new BusinessException(
                                ApiCode.VALIDATION_FAILED, "baseUrl 需以 http:// 或 https:// 开头"));
        mockMvc.perform(
                        put("/api/admin/llm/config")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"enabled\":false,\"baseUrl\":\"ftp://x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()))
                .andExpect(jsonPath("$.message").value("baseUrl 需以 http:// 或 https:// 开头"));

        when(tutorService.updateAdminConfig(any(), eq(1L)))
                .thenThrow(
                        new BusinessException(
                                ApiCode.VALIDATION_FAILED,
                                "答疑凭证不齐：请在管理端模型页「答疑 LLM」卡录入 API 密钥（保存即时生效），"
                                        + "或设置环境变量 YILIAN_LLM_API_KEY"));
        mockMvc.perform(
                        put("/api/admin/llm/config")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"enabled\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));
    }

    /** [AC-1] 审计口径：PUT 标 @OperationLog(LLM_CONFIG_UPDATE)，GET 不标。 */
    @Test
    void writeCarriesAuditAndReadDoesNot_ac1() throws Exception {
        OperationLog put =
                AdminLlmConfigController.class
                        .getDeclaredMethod(
                                "updateConfig",
                                LlmConfigUpdateRequest.class,
                                YilianUserPrincipal.class)
                        .getAnnotation(OperationLog.class);
        assertThat(put).isNotNull();
        assertThat(put.module()).isEqualTo("llm");
        assertThat(put.action()).isEqualTo("LLM_CONFIG_UPDATE");

        OperationLog get =
                AdminLlmConfigController.class
                        .getDeclaredMethod("getConfig", YilianUserPrincipal.class)
                        .getAnnotation(OperationLog.class);
        assertThat(get).isNull();
    }
}

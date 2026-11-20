package io.github.codejinxz.yilian.practice.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.logging.ErrorLogRecorder;
import io.github.codejinxz.yilian.common.security.PasswordChangeRequiredInterceptor;
import io.github.codejinxz.yilian.common.security.RestAccessDeniedHandler;
import io.github.codejinxz.yilian.common.security.RestAuthenticationEntryPoint;
import io.github.codejinxz.yilian.common.security.SecurityConfig;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.practice.service.AdminAttemptRawService;
import io.github.codejinxz.yilian.practice.service.vo.AdminAttemptRawView;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.node.JsonNodeFactory;

/**
 * 查看答题原文端点（API §8.7）的 HTTP 层切片测试：管理员 200 信封与字段形状、 鉴权矩阵（匿名 401 / 非管理员 403）、缺原因 40000、提交不存在 40403、
 * 缺请求体 40001； 原因校验与审计落行断言在 Service 层测试覆盖。
 */
@WebMvcTest(controllers = AdminAttemptRawController.class)
@Import({
    SecurityConfig.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    PasswordChangeRequiredInterceptor.class
})
class AdminAttemptRawControllerTest {

    private static final YilianUserPrincipal ADMIN =
            YilianUserPrincipal.of(1L, "admin", "ADMIN", true);

    private static final YilianUserPrincipal USER =
            YilianUserPrincipal.of(7L, "learner", "USER", true);

    private static final String URL =
            "/api/admin/attempts/3f0e5c1a-0000-0000-0000-000000000001/raw";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private AdminAttemptRawService adminAttemptRawService;

    @MockitoBean private JwtDecoder jwtDecoder;

    @MockitoBean private ErrorLogRecorder errorLogRecorder;

    /** 匿名访问：401 信封（code=40100），不触达 Service。 */
    @Test
    void viewRawRejectsAnonymousWith401() throws Exception {
        mockMvc.perform(
                        post(URL)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"评分申诉\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        verifyNoInteractions(adminAttemptRawService);
    }

    /** 非 ADMIN 登录用户：403 信封（code=40300），不触达 Service。 */
    @Test
    void viewRawRejectsNonAdminWith403() throws Exception {
        mockMvc.perform(
                        post(URL)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"评分申诉\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));
        verifyNoInteractions(adminAttemptRawService);
    }

    /** 管理员查看成功：ApiResponse 信封携带 §8.7 全部字段（answer/grading 原样 JSON、审计 id 字符串）。 */
    @Test
    void viewRawReturnsEnvelopeWithAllContractFields() throws Exception {
        when(adminAttemptRawService.viewRaw(
                        eq("3f0e5c1a-0000-0000-0000-000000000001"),
                        eq("评分申诉"),
                        eq(1L),
                        eq("admin"),
                        eq("ADMIN")))
                .thenReturn(rawView());
        mockMvc.perform(
                        post(URL)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"评分申诉\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(
                        jsonPath("$.data.attemptId").value("3f0e5c1a-0000-0000-0000-000000000001"))
                .andExpect(jsonPath("$.data.userId").value("2"))
                .andExpect(jsonPath("$.data.sentenceId").value("01JAAAAAAAAAAAAAAAAAAAAAAAAA"))
                .andExpect(jsonPath("$.data.answer.mode").value("CLOZE"))
                .andExpect(jsonPath("$.data.grading.id").value("100"))
                .andExpect(jsonPath("$.data.grading.score").value(80))
                .andExpect(jsonPath("$.data.auditId").value("101"));
        verify(adminAttemptRawService)
                .viewRaw(
                        eq("3f0e5c1a-0000-0000-0000-000000000001"),
                        eq("评分申诉"),
                        eq(1L),
                        eq("admin"),
                        eq("ADMIN"));
    }

    /** 缺原因：Service 抛 40000 → 400 信封；缺请求体为 40001 且不触达 Service。 */
    @Test
    void viewRawRejectsBlankReasonWith40000AndMissingBodyWith40001() throws Exception {
        when(adminAttemptRawService.viewRaw(any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(ApiCode.VALIDATION_FAILED, "查看答题原文必须填写原因"));

        mockMvc.perform(
                        post(URL)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));

        mockMvc.perform(
                        post(URL)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.MALFORMED_REQUEST.code()));
    }

    /** 提交不存在：404 信封（code=40403）。 */
    @Test
    void viewRawRejectsMissingAttemptWith40403() throws Exception {
        when(adminAttemptRawService.viewRaw(any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(ApiCode.ATTEMPT_NOT_FOUND));

        mockMvc.perform(
                        post(URL)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"评分申诉\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiCode.ATTEMPT_NOT_FOUND.code()));
    }

    /** 构造 §8.7 契约示例形状的回执。 */
    private static AdminAttemptRawView rawView() {
        return new AdminAttemptRawView(
                "3f0e5c1a-0000-0000-0000-000000000001",
                "2",
                "01JAAAAAAAAAAAAAAAAAAAAAAAAA",
                JsonNodeFactory.instance.objectNode().put("schemaVersion", 1).put("mode", "CLOZE"),
                new AdminAttemptRawView.Grading(
                        "100", 80, JsonNodeFactory.instance.objectNode().put("score", 80)),
                "101");
    }
}

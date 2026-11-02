package io.github.codejinxz.yilian.admin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.codejinxz.yilian.admin.service.AdminAuditEventService;
import io.github.codejinxz.yilian.admin.service.dto.AdminAuditEventRequest;
import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.logging.ErrorLogRecorder;
import io.github.codejinxz.yilian.common.security.PasswordChangeRequiredInterceptor;
import io.github.codejinxz.yilian.common.security.RestAccessDeniedHandler;
import io.github.codejinxz.yilian.common.security.RestAuthenticationEntryPoint;
import io.github.codejinxz.yilian.common.security.SecurityConfig;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;

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
 * 审计事件端点（API §8.12）的 HTTP 层切片测试 [AC-4]：管理员 200 返回字符串 id、白名单越界 40000、 鉴权矩阵（匿名 401 / 非管理员
 * 403）；白名单清单与落库字段由 {@code AdminAuditEventServiceImplTest} 负责。
 */
@WebMvcTest(controllers = AdminAuditEventController.class)
@Import({
    SecurityConfig.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    PasswordChangeRequiredInterceptor.class
})
class AdminAuditEventControllerTest {

    private static final YilianUserPrincipal ADMIN =
            YilianUserPrincipal.of(1L, "admin", "ADMIN", true);

    private static final YilianUserPrincipal USER =
            YilianUserPrincipal.of(7L, "learner", "USER", true);

    @Autowired private MockMvc mockMvc;

    @MockitoBean private AdminAuditEventService adminAuditEventService;

    @MockitoBean private JwtDecoder jwtDecoder;

    @MockitoBean private ErrorLogRecorder errorLogRecorder;

    /** [AC-4] 匿名访问：401 信封，不触达 Service。 */
    @Test
    void recordRejectsAnonymousWith401_ac4() throws Exception {
        mockMvc.perform(post("/api/admin/audit-events").with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        verifyNoInteractions(adminAuditEventService);
    }

    /** [AC-4] 非 ADMIN 登录用户：403 信封，不触达 Service。 */
    @Test
    void recordRejectsNonAdminWith403_ac4() throws Exception {
        mockMvc.perform(
                        post("/api/admin/audit-events")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));
        verifyNoInteractions(adminAuditEventService);
    }

    /** [AC-4] 管理员记录事件：200 返回字符串化 id（与前端 z.string() 解析契约一致）。 */
    @Test
    void recordReturnsStringIdOnSuccess_ac4() throws Exception {
        when(adminAuditEventService.recordEvent(
                        org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.eq("admin"),
                        org.mockito.ArgumentMatchers.eq("ADMIN"),
                        any(AdminAuditEventRequest.class)))
                .thenReturn(901L);

        mockMvc.perform(
                        post("/api/admin/audit-events")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"action\":\"RESET_PASSWORD\",\"targetId\":\"7\","
                                                + "\"reason\":\"用户忘记密码\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.id").value("901"));

        verify(adminAuditEventService)
                .recordEvent(
                        org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.eq("admin"),
                        org.mockito.ArgumentMatchers.eq("ADMIN"),
                        any(AdminAuditEventRequest.class));
    }

    /** [AC-4] 白名单越界：Service 抛 40000 → 400 信封透传；缺请求体 40001。 */
    @Test
    void recordRejectsUnknownActionWith40000_ac4() throws Exception {
        when(adminAuditEventService.recordEvent(any(), any(), any(), any()))
                .thenThrow(new BusinessException(ApiCode.VALIDATION_FAILED, "不支持的审计动作"));

        mockMvc.perform(
                        post("/api/admin/audit-events")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"action\":\"HACK_THE_PLANET\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));

        mockMvc.perform(
                        post("/api/admin/audit-events")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.MALFORMED_REQUEST.code()));
    }
}

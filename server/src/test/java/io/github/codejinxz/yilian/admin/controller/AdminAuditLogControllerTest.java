package io.github.codejinxz.yilian.admin.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.codejinxz.yilian.admin.service.AdminLogService;
import io.github.codejinxz.yilian.admin.service.vo.AdminAuditLogView;
import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.PageResponse;
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
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

/**
 * 操作日志检索端点（API §8.4）的 HTTP 层切片测试 [AC-1]：管理员 200 与 PageResponse 形状、筛选参数透传、 鉴权矩阵（匿名 401 / 非管理员
 * 403）；时间兜底、关键字转义与 size 归一由 {@code AdminLogServiceImplTest} 负责。
 */
@WebMvcTest(controllers = AdminAuditLogController.class)
@Import({
    SecurityConfig.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    PasswordChangeRequiredInterceptor.class
})
class AdminAuditLogControllerTest {

    private static final YilianUserPrincipal ADMIN =
            YilianUserPrincipal.of(1L, "admin", "ADMIN", true);

    private static final YilianUserPrincipal USER =
            YilianUserPrincipal.of(7L, "learner", "USER", true);

    @Autowired private MockMvc mockMvc;

    @MockitoBean private AdminLogService adminLogService;

    @MockitoBean private JwtDecoder jwtDecoder;

    @MockitoBean private ErrorLogRecorder errorLogRecorder;

    /** [AC-1] 匿名访问：401 信封（code=40100），不触达 Service。 */
    @Test
    void listRejectsAnonymousWith401_ac1() throws Exception {
        mockMvc.perform(get("/api/admin/audit-logs"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        verifyNoInteractions(adminLogService);
    }

    /** [AC-1] 非 ADMIN 登录用户：403 信封（code=40300），不触达 Service。 */
    @Test
    void listRejectsNonAdminWith403_ac1() throws Exception {
        mockMvc.perform(
                        get("/api/admin/audit-logs")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));
        verifyNoInteractions(adminLogService);
    }

    /** [AC-1] 管理员检索：全部筛选参数透传，200 信封为 PageResponse + AdminAuditLogView 形状。 */
    @Test
    void listForwardsFiltersAndReturnsPageShape_ac1() throws Exception {
        when(adminLogService.searchAuditLogs(
                        "停用", "admin", "USER_DISABLE", "2", "2026-10-01", "2026-10-31", 2, 5))
                .thenReturn(PageResponse.of(List.of(auditLogView()), 2, 5, 1));

        mockMvc.perform(
                        get("/api/admin/audit-logs")
                                .queryParam("page", "2")
                                .queryParam("size", "5")
                                .queryParam("keyword", "停用")
                                .queryParam("module", "admin")
                                .queryParam("action", "USER_DISABLE")
                                .queryParam("actorId", "2")
                                .queryParam("from", "2026-10-01")
                                .queryParam("to", "2026-10-31")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.message").value(ApiCode.SUCCESS.message()))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(5))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.items[0].id").value(900))
                .andExpect(jsonPath("$.data.items[0].module").value("admin"))
                .andExpect(jsonPath("$.data.items[0].actorId").value(2))
                .andExpect(jsonPath("$.data.items[0].actorName").value("admin"))
                .andExpect(jsonPath("$.data.items[0].actorRole").value("ADMIN"))
                .andExpect(jsonPath("$.data.items[0].clientIp").value("127.0.0.1"))
                .andExpect(jsonPath("$.data.items[0].action").value("USER_DISABLE"))
                .andExpect(jsonPath("$.data.items[0].targetType").value("USER"))
                .andExpect(jsonPath("$.data.items[0].targetId").value("7"))
                .andExpect(jsonPath("$.data.items[0].reason").value("用户申请"))
                .andExpect(jsonPath("$.data.items[0].outcome").value("SUCCESS"))
                .andExpect(jsonPath("$.data.items[0].durationMs").value(12))
                .andExpect(jsonPath("$.data.items[0].createdAt").value("2026-10-31T02:00:00Z"));

        verify(adminLogService)
                .searchAuditLogs(
                        "停用", "admin", "USER_DISABLE", "2", "2026-10-01", "2026-10-31", 2, 5);
    }

    /** [AC-1] 缺省参数：page/size 走默认值（1/20），其余参数为 null。 */
    @Test
    void listUsesDefaultsWhenParamsAbsent_ac1() throws Exception {
        when(adminLogService.searchAuditLogs(null, null, null, null, null, null, 1, 20))
                .thenReturn(PageResponse.of(List.of(), 1, 20, 0));

        mockMvc.perform(
                        get("/api/admin/audit-logs")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());

        verify(adminLogService).searchAuditLogs(null, null, null, null, null, null, 1, 20);
    }

    /**
     * 构造操作日志视图样例。
     *
     * @return 视图
     */
    private static AdminAuditLogView auditLogView() {
        return new AdminAuditLogView(
                900L,
                "admin",
                2L,
                "admin",
                "ADMIN",
                "127.0.0.1",
                "USER_DISABLE",
                "USER",
                "7",
                "用户申请",
                "SUCCESS",
                12,
                Instant.parse("2026-10-31T02:00:00Z"));
    }
}

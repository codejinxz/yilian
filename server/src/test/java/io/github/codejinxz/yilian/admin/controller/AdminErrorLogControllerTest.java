package io.github.codejinxz.yilian.admin.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.codejinxz.yilian.admin.service.AdminLogService;
import io.github.codejinxz.yilian.admin.service.vo.AdminErrorLogDetailView;
import io.github.codejinxz.yilian.admin.service.vo.AdminErrorLogView;
import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
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
 * 异常日志端点（API §8.5–§8.6）的 HTTP 层切片测试 [AC-2]：列表剥离 stackTrace、详情含 stackTrace、 缺失 40400、鉴权矩阵（匿名 401 /
 * 非管理员 403）；时间兜底与关键字转义由 {@code AdminLogServiceImplTest} 负责。
 */
@WebMvcTest(controllers = AdminErrorLogController.class)
@Import({
    SecurityConfig.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    PasswordChangeRequiredInterceptor.class
})
class AdminErrorLogControllerTest {

    private static final YilianUserPrincipal ADMIN =
            YilianUserPrincipal.of(1L, "admin", "ADMIN", true);

    private static final YilianUserPrincipal USER =
            YilianUserPrincipal.of(7L, "learner", "USER", true);

    @Autowired private MockMvc mockMvc;

    @MockitoBean private AdminLogService adminLogService;

    @MockitoBean private JwtDecoder jwtDecoder;

    @MockitoBean private ErrorLogRecorder errorLogRecorder;

    /** [AC-2] 匿名访问列表与详情：401 信封，不触达 Service。 */
    @Test
    void endpointsRejectAnonymousWith401_ac2() throws Exception {
        mockMvc.perform(get("/api/admin/error-logs"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        mockMvc.perform(get("/api/admin/error-logs/{id}", 200))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        verifyNoInteractions(adminLogService);
    }

    /** [AC-2] 非 ADMIN 登录用户：列表与详情均 403 信封，不触达 Service。 */
    @Test
    void endpointsRejectNonAdminWith403_ac2() throws Exception {
        mockMvc.perform(
                        get("/api/admin/error-logs")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));
        mockMvc.perform(
                        get("/api/admin/error-logs/{id}", 200)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));
        verifyNoInteractions(adminLogService);
    }

    /** [AC-2] 管理员检索列表：筛选参数透传，200 信封且列表项不含 stackTrace 字段。 */
    @Test
    void listForwardsFiltersAndStripsStackTrace_ac2() throws Exception {
        when(adminLogService.searchErrorLogs("IllegalState", "practice", "2026-10-01", null, 1, 20))
                .thenReturn(PageResponse.of(List.of(errorLogView()), 1, 20, 1));

        mockMvc.perform(
                        get("/api/admin/error-logs")
                                .queryParam("keyword", "IllegalState")
                                .queryParam("module", "practice")
                                .queryParam("from", "2026-10-01")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(200))
                .andExpect(jsonPath("$.data.items[0].traceId").value("abc123"))
                .andExpect(jsonPath("$.data.items[0].module").value("practice"))
                .andExpect(
                        jsonPath("$.data.items[0].exceptionClass")
                                .value("java.lang.IllegalStateException"))
                .andExpect(jsonPath("$.data.items[0].message").value("boom"))
                .andExpect(jsonPath("$.data.items[0].requestMethod").value("POST"))
                .andExpect(jsonPath("$.data.items[0].requestPath").value("/api/practice/attempts"))
                .andExpect(jsonPath("$.data.items[0].userId").value(2))
                .andExpect(jsonPath("$.data.items[0].operator").value("learner"))
                .andExpect(jsonPath("$.data.items[0].clientIp").value("127.0.0.1"))
                .andExpect(jsonPath("$.data.items[0].createdAt").value("2026-10-24T05:30:00Z"))
                // 列表剥离堆栈：stackTrace 字段不存在。
                .andExpect(jsonPath("$.data.items[0].stackTrace").doesNotExist());

        verify(adminLogService)
                .searchErrorLogs("IllegalState", "practice", "2026-10-01", null, 1, 20);
    }

    /** [AC-2] 管理员查详情：200 含 stackTrace；缺失回 40400 信封。 */
    @Test
    void detailReturnsStackTraceAndMissingMapsTo40400_ac2() throws Exception {
        when(adminLogService.errorLogDetail(200L)).thenReturn(errorLogDetailView());

        mockMvc.perform(
                        get("/api/admin/error-logs/{id}", 200)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.id").value(200))
                .andExpect(
                        jsonPath("$.data.exceptionClass").value("java.lang.IllegalStateException"))
                .andExpect(
                        jsonPath("$.data.stackTrace")
                                .value(
                                        "java.lang.IllegalStateException: boom\n\tat yilian.Application.main"));

        when(adminLogService.errorLogDetail(404L))
                .thenThrow(new BusinessException(ApiCode.NOT_FOUND));

        mockMvc.perform(
                        get("/api/admin/error-logs/{id}", 404)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiCode.NOT_FOUND.code()))
                .andExpect(jsonPath("$.message").value("请求的资源不存在"));
    }

    /**
     * 构造异常日志列表行（不含堆栈）。
     *
     * @return 列表行视图
     */
    private static AdminErrorLogView errorLogView() {
        return new AdminErrorLogView(
                200L,
                "abc123",
                "practice",
                "java.lang.IllegalStateException",
                "boom",
                "POST",
                "/api/practice/attempts",
                2L,
                "learner",
                "127.0.0.1",
                Instant.parse("2026-10-24T05:30:00Z"));
    }

    /**
     * 构造异常详情视图（含堆栈）。
     *
     * @return 详情视图
     */
    private static AdminErrorLogDetailView errorLogDetailView() {
        return new AdminErrorLogDetailView(
                200L,
                "abc123",
                "practice",
                "java.lang.IllegalStateException",
                "boom",
                "POST",
                "/api/practice/attempts",
                2L,
                "learner",
                "127.0.0.1",
                Instant.parse("2026-10-24T05:30:00Z"),
                "java.lang.IllegalStateException: boom\n\tat yilian.Application.main");
    }
}

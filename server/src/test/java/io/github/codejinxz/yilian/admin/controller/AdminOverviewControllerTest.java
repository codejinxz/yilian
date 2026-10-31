package io.github.codejinxz.yilian.admin.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.codejinxz.yilian.admin.service.AdminOverviewService;
import io.github.codejinxz.yilian.admin.service.vo.AdminOverviewView;
import io.github.codejinxz.yilian.common.api.ApiCode;
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
 * 概览端点（API §8.1）的 HTTP 层切片测试 [AC-2]：管理员 200 与形状断言、鉴权矩阵（匿名 401 / 非管理员 403）[AC-1]； 指标口径与补 0 逻辑由
 * {@code AdminOverviewServiceImplTest} 负责，此处 Service 用 Mockito 替身。
 */
@WebMvcTest(controllers = AdminOverviewController.class)
@Import({
    SecurityConfig.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    PasswordChangeRequiredInterceptor.class
})
class AdminOverviewControllerTest {

    private static final YilianUserPrincipal ADMIN =
            YilianUserPrincipal.of(1L, "admin", "ADMIN", true);

    private static final YilianUserPrincipal USER =
            YilianUserPrincipal.of(7L, "learner", "USER", true);

    @Autowired private MockMvc mockMvc;

    @MockitoBean private AdminOverviewService adminOverviewService;

    @MockitoBean private JwtDecoder jwtDecoder;

    @MockitoBean private ErrorLogRecorder errorLogRecorder;

    /** [AC-2][AC-1] 匿名访问 /api/admin/overview：401 信封（code=40100），不触达 Service。 */
    @Test
    void overviewRejectsAnonymousWith401_ac1() throws Exception {
        mockMvc.perform(get("/api/admin/overview"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        verifyNoInteractions(adminOverviewService);
    }

    /** [AC-1] 非 ADMIN 登录用户访问概览：403 信封（code=40300），不触达 Service。 */
    @Test
    void overviewRejectsNonAdminWith403_ac1() throws Exception {
        mockMvc.perform(
                        get("/api/admin/overview")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));
        verifyNoInteractions(adminOverviewService);
    }

    /** [AC-2] 管理员请求概览：200 信封，metrics/active7d/todos/activity 四段形状与字段名齐全。 */
    @Test
    void adminGetsOverviewWithEnvelopeShape_ac2() throws Exception {
        when(adminOverviewService.overview()).thenReturn(overview());

        mockMvc.perform(
                        get("/api/admin/overview")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.message").value(ApiCode.SUCCESS.message()))
                .andExpect(jsonPath("$.data.metrics.totalUsers").value(12))
                .andExpect(jsonPath("$.data.metrics.activeToday").value(3))
                .andExpect(jsonPath("$.data.metrics.totalAttempts").value(456))
                .andExpect(jsonPath("$.data.metrics.estimatedCost").value(0))
                .andExpect(jsonPath("$.data.active7d.length()").value(1))
                .andExpect(jsonPath("$.data.active7d[0].date").value("2026-10-30"))
                .andExpect(jsonPath("$.data.active7d[0].count").value(5))
                .andExpect(jsonPath("$.data.todos.length()").value(1))
                .andExpect(jsonPath("$.data.todos[0].id").value("import"))
                .andExpect(jsonPath("$.data.todos[0].href").value("/admin?tab=banks"))
                .andExpect(jsonPath("$.data.activity[0].id").value("9"))
                .andExpect(jsonPath("$.data.activity[0].who").value("admin"))
                .andExpect(jsonPath("$.data.activity[0].what").value("USER_STATUS_CHANGE · user 7"))
                .andExpect(jsonPath("$.data.activity[0].at").value("2026-10-31T02:00:00Z"));
    }

    /**
     * 构造概览视图样例。
     *
     * @return 概览视图
     */
    private static AdminOverviewView overview() {
        return new AdminOverviewView(
                new AdminOverviewView.Metrics(12L, 3L, 456L, 0L),
                List.of(new AdminOverviewView.DailyActivity("2026-10-30", 5L)),
                List.of(
                        new AdminOverviewView.TodoItem(
                                "import", "导入题库", "uploads 页签", "/admin?tab=banks")),
                List.of(
                        new AdminOverviewView.ActivityItem(
                                "9",
                                "admin",
                                "USER_STATUS_CHANGE · user 7",
                                Instant.parse("2026-10-31T02:00:00Z").toString())));
    }
}

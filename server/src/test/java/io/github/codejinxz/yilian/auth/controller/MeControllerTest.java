package io.github.codejinxz.yilian.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.codejinxz.yilian.auth.service.AuthService;
import io.github.codejinxz.yilian.auth.vo.AccountExportView;
import io.github.codejinxz.yilian.auth.vo.UserSettingsResponse;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;

/**
 * 用户设置接口（API §4.9）的 MVC 切片测试：真实 {@link SecurityConfig} 与强制改密拦截器， Service 用 Mockito 替身。
 *
 * <p>注解级校验明细（越界/精度/模式）由 {@code UserSettingsRequestValidationTest} 负责；IANA 时区等
 * 服务层校验由服务实现测试负责。此处锁定鉴权边界、信封形状、字段透传与 40303 首登拦截。
 */
@WebMvcTest(controllers = MeController.class)
@Import({
    SecurityConfig.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    PasswordChangeRequiredInterceptor.class
})
class MeControllerTest {

    private static final YilianUserPrincipal USER =
            YilianUserPrincipal.of(7L, "learner", "USER", true);

    /** 首次登录尚未改密的主体；除认证白名单外应被 40303 拦下。 */
    private static final YilianUserPrincipal FRESH_USER =
            YilianUserPrincipal.of(8L, "freshman", "USER", true, false);

    @Autowired private MockMvc mockMvc;

    @MockitoBean private AuthService authService;

    @MockitoBean private JwtDecoder jwtDecoder;

    @MockitoBean private ErrorLogRecorder errorLogRecorder;

    /** 匿名读/写设置一律 401 信封（code=40100），不触达 Service。 */
    @Test
    void settingsRejectAnonymousWith401() throws Exception {
        mockMvc.perform(get("/api/me/settings"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        mockMvc.perform(put("/api/me/settings").with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        verifyNoInteractions(authService);
    }

    /** 读取设置成功：200 信封承载 §4.9 全部字段（时区/目标/评分标准/保留率/主题/通知）。 */
    @Test
    void getSettingsReturnsEnvelope() throws Exception {
        when(authService.getSettings(USER)).thenReturn(userSettings());

        mockMvc.perform(
                        get("/api/me/settings")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.timezone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.data.dailyGoal").value(3))
                .andExpect(jsonPath("$.data.ratingStandard").value("TEM8"))
                .andExpect(jsonPath("$.data.desiredRetention").value(0.9))
                .andExpect(jsonPath("$.data.theme").value("paper"))
                .andExpect(jsonPath("$.data.notificationsEnabled").value(true));

        verify(authService).getSettings(USER);
    }

    /** 更新设置成功：请求体透传 Service，返回更新后的设置视图。 */
    @Test
    void putSettingsDelegatesToServiceAndReturnsUpdatedView() throws Exception {
        when(authService.updateSettings(eq(USER), any()))
                .thenReturn(
                        new UserSettingsResponse(
                                "Asia/Shanghai",
                                5,
                                "TEM8",
                                new BigDecimal("0.900"),
                                "paper",
                                false));

        mockMvc.perform(
                        put("/api/me/settings")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"timezone\":\"Asia/Shanghai\",\"dailyGoal\":5,"
                                                + "\"notificationsEnabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.dailyGoal").value(5))
                .andExpect(jsonPath("$.data.notificationsEnabled").value(false));

        verify(authService).updateSettings(eq(USER), any());
    }

    /** 字段越界（dailyGoal=9）：40000 信封且 data.errors 带字段级中文明细，不触达 Service。 */
    @Test
    void putSettingsRejectsInvalidDailyGoalWithFieldDetail() throws Exception {
        mockMvc.perform(
                        put("/api/me/settings")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"dailyGoal\":9}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()))
                .andExpect(jsonPath("$.data.errors[0].field").value("dailyGoal"));
        verifyNoInteractions(authService);
    }

    /** 请求体不是合法 JSON：按 40001 收敛为统一信封，不触达 Service。 */
    @Test
    void putSettingsMalformedBodyIs40001() throws Exception {
        mockMvc.perform(
                        put("/api/me/settings")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{dailyGoal:}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.MALFORMED_REQUEST.code()));
        verifyNoInteractions(authService);
    }

    /** 首次登录未改密：/api/me/settings 不在认证白名单内，应被 40303 拦截（API §1.4）。 */
    @Test
    void putSettingsRequiresPasswordChangeWith40303() throws Exception {
        mockMvc.perform(
                        put("/api/me/settings")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        FRESH_USER, null, "ROLE_USER")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"dailyGoal\":3}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.PASSWORD_CHANGE_REQUIRED.code()));
        verifyNoInteractions(authService);
    }

    /** [AC-2] 匿名导出一律 401 信封（code=40100），不触达 Service。 */
    @Test
    void exportRejectAnonymousWith401() throws Exception {
        mockMvc.perform(get("/api/me/export"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        verifyNoInteractions(authService);
    }

    /** [AC-2] 导出成功：响应体为裸导出 JSON（非统一信封）+ Content-Disposition 附件头。 */
    @Test
    void exportReturnsRawJsonWithAttachmentHeader() throws Exception {
        when(authService.exportAccountData(USER)).thenReturn(exportView());

        MvcResult result =
                mockMvc.perform(
                                get("/api/me/export")
                                        .with(
                                                authentication(
                                                        new TestingAuthenticationToken(
                                                                USER, null, "ROLE_USER"))))
                        .andExpect(status().isOk())
                        .andExpect(
                                header().string(
                                                "Content-Disposition",
                                                org.hamcrest.Matchers.containsString("attachment")))
                        .andExpect(
                                header().string(
                                                "Content-Disposition",
                                                org.hamcrest.Matchers.matchesPattern(
                                                        ".*yilian-export-\\d{4}-\\d{2}-\\d{2}\\.json.*")))
                        .andReturn();

        // 裸导出 JSON：schemaVersion 在顶层而非信封 data 内；body 是可归档的纯数据。
        String body = result.getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).contains("\"schemaVersion\":1");
        org.assertj.core.api.Assertions.assertThat(body).contains("\"username\":\"learner\"");
        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("\"code\":0,\"message\":\"success\"");
        verify(authService).exportAccountData(USER);
    }

    /**
     * 构造一份最小导出视图（空切片）。
     *
     * @return 导出视图
     */
    private static AccountExportView exportView() {
        return new AccountExportView(
                1,
                "2026-11-19T00:00:00Z",
                new AccountExportView.AccountBlock(
                        7L,
                        "learner",
                        "learner@example.com",
                        "2026-11-19T00:00:00Z",
                        new AccountExportView.SettingsBlock(
                                "Asia/Shanghai",
                                3,
                                "TEM8",
                                new BigDecimal("0.900"),
                                "paper",
                                false)),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of());
    }

    /**
     * 构造一份默认设置视图。
     *
     * @return 设置视图
     */
    private static UserSettingsResponse userSettings() {
        return new UserSettingsResponse(
                "Asia/Shanghai", 3, "TEM8", new BigDecimal("0.900"), "paper", true);
    }
}

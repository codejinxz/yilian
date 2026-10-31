package io.github.codejinxz.yilian.auth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.codejinxz.yilian.auth.service.AuthService;
import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.logging.ErrorLogRecorder;
import io.github.codejinxz.yilian.common.ratelimit.RateLimit;
import io.github.codejinxz.yilian.common.security.PasswordChangeRequiredInterceptor;
import io.github.codejinxz.yilian.common.security.RestAccessDeniedHandler;
import io.github.codejinxz.yilian.common.security.RestAuthenticationEntryPoint;
import io.github.codejinxz.yilian.common.security.SecurityConfig;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.practice.service.PracticeService;
import io.github.codejinxz.yilian.practice.service.dto.GuestUpgradeCommand;
import io.github.codejinxz.yilian.practice.service.vo.GuestUpgradeView;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

/** 游客升级端点切片测试：鉴权边界（匿名 401）、信封结构与限流口径（5 次/小时）。 */
@WebMvcTest(controllers = AuthController.class)
@Import({
    SecurityConfig.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    PasswordChangeRequiredInterceptor.class
})
class AuthGuestUpgradeControllerTest {

    private static final YilianUserPrincipal USER =
            YilianUserPrincipal.of(7L, "learner", "USER", true);

    @Autowired private MockMvc mockMvc;

    @MockitoBean private AuthService authService;

    @MockitoBean private io.github.codejinxz.yilian.auth.service.CaptchaService captchaService;

    @MockitoBean private PracticeService practiceService;

    @MockitoBean private JwtDecoder jwtDecoder;

    @MockitoBean private ErrorLogRecorder errorLogRecorder;

    /** 匿名调用 401（端点要求 USER）。 */
    @Test
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(
                        post("/api/auth/guest-upgrade")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"attempts\": []}"))
                .andExpect(status().isUnauthorized());
    }

    /** 迁移成功：200 信封返回 imported/skipped/conflicts。 */
    @Test
    void guestUpgradeReturnsCounts() throws Exception {
        when(practiceService.guestUpgrade(eq(7L), any(GuestUpgradeCommand.class)))
                .thenReturn(new GuestUpgradeView(10, 2, 0));
        mockMvc.perform(
                        post("/api/auth/guest-upgrade")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"attempts\": []}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.imported").value(10))
                .andExpect(jsonPath("$.data.skipped").value(2))
                .andExpect(jsonPath("$.data.conflicts").value(0));
    }

    /** 载荷冲突：Service 抛 40903 → HTTP 409 透传。 */
    @Test
    void conflictMapsTo409() throws Exception {
        when(practiceService.guestUpgrade(eq(7L), any(GuestUpgradeCommand.class)))
                .thenThrow(new BusinessException(ApiCode.ATTEMPT_ID_REUSED));
        mockMvc.perform(
                        post("/api/auth/guest-upgrade")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"attempts\": [{\"attemptId\": \""
                                                + UUID.randomUUID()
                                                + "\", \"sentenceId\": \"01JX\", \"sentenceVersionId\": \"1\","
                                                + " \"mode\": \"CLOZE\", \"kind\": \"FIRST\","
                                                + " \"answer\": {\"schemaVersion\": 1}}]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ApiCode.ATTEMPT_ID_REUSED.code()));
    }

    /** 限流口径：5 次/小时、账号维度。 */
    @Test
    void rateLimitIsFivePerHourPerUser() throws Exception {
        RateLimit rateLimit =
                AuthController.class
                        .getDeclaredMethod(
                                "guestUpgrade",
                                GuestUpgradeCommand.class,
                                YilianUserPrincipal.class)
                        .getAnnotation(RateLimit.class);
        org.assertj.core.api.Assertions.assertThat(rateLimit).isNotNull();
        org.assertj.core.api.Assertions.assertThat(rateLimit.scene()).isEqualTo("guest-upgrade");
        org.assertj.core.api.Assertions.assertThat(rateLimit.limit()).isEqualTo(5);
        org.assertj.core.api.Assertions.assertThat(rateLimit.windowSeconds()).isEqualTo(3600);
    }
}

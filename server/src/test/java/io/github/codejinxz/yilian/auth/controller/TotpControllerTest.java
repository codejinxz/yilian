package io.github.codejinxz.yilian.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.codejinxz.yilian.auth.dto.TotpActivateRequest;
import io.github.codejinxz.yilian.auth.dto.TotpDisableRequest;
import io.github.codejinxz.yilian.auth.dto.TotpSetupRequest;
import io.github.codejinxz.yilian.auth.service.TotpAuthService;
import io.github.codejinxz.yilian.auth.vo.TotpActivationView;
import io.github.codejinxz.yilian.auth.vo.TotpSetupView;
import io.github.codejinxz.yilian.auth.vo.TotpStatusView;
import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.logging.ErrorLogRecorder;
import io.github.codejinxz.yilian.common.ratelimit.RateLimit;
import io.github.codejinxz.yilian.common.ratelimit.RateLimitDimension;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

/**
 * 两步验证自助端点（API §4.10）的 HTTP 层切片测试 [AC-4]：鉴权矩阵（匿名 401）、参数校验、 信封形状与限流注解契约；业务分支（错误密码/错码/密钥
 * 漂移）由集成测试覆盖，此处 Service 用 Mockito 替身。
 */
@WebMvcTest(controllers = TotpController.class)
@Import({
    SecurityConfig.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    PasswordChangeRequiredInterceptor.class
})
class TotpControllerTest {

    private static final YilianUserPrincipal USER =
            YilianUserPrincipal.of(7L, "learner", "USER", true);

    @Autowired private MockMvc mockMvc;

    @MockitoBean private TotpAuthService totpAuthService;

    @MockitoBean private JwtDecoder jwtDecoder;

    @MockitoBean private ErrorLogRecorder errorLogRecorder;

    /** 当前用户的认证替身（与 AdminUserControllerTest 同款）。 */
    private static Authentication auth() {
        return new TestingAuthenticationToken(USER, null, "ROLE_USER");
    }

    /** [AC-4] 匿名访问四个端点：401 信封（code=40100），不触达 Service。 */
    @Test
    void endpointsRejectAnonymousWith401_ac4() throws Exception {
        mockMvc.perform(get("/api/auth/2fa/status"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        mockMvc.perform(
                        post("/api/auth/2fa/setup")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"password\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        mockMvc.perform(
                        post("/api/auth/2fa/activate")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"code\":\"12345678\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(
                        post("/api/auth/2fa/disable")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"password\":\"x\",\"code\":\"12345678\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(totpAuthService);
    }

    /** [AC-4] status 端点：200 信封投影 enabled/enrolledAt/剩余恢复码数，无密钥材料。 */
    @Test
    void statusReturnsEnvelopeWithoutSecretMaterial_ac4() throws Exception {
        when(totpAuthService.status(USER))
                .thenReturn(new TotpStatusView(true, Instant.parse("2026-11-18T00:00:00Z"), 9));

        mockMvc.perform(get("/api/auth/2fa/status").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.enrolledAt").value("2026-11-18T00:00:00Z"))
                .andExpect(jsonPath("$.data.remainingRecoveryCodes").value(9));
    }

    /** [AC-4] setup 端点：透传密码确认，200 回 Base32 密钥与 otpauth URI（仅一次展示）。 */
    @Test
    void setupForwardsPasswordAndReturnsOneTimeSecret_ac4() throws Exception {
        when(totpAuthService.setup(eq(USER), eq(new TotpSetupRequest("current-password"))))
                .thenReturn(
                        new TotpSetupView(
                                "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ",
                                "otpauth://totp/Yilian:learner?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
                                        + "&issuer=Yilian&algorithm=SHA1&digits=8&period=30"));

        mockMvc.perform(
                        post("/api/auth/2fa/setup")
                                .with(authentication(auth()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"password\":\"current-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.secret").value("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"))
                .andExpect(jsonPath("$.data.otpauthUri").isNotEmpty());

        verify(totpAuthService).setup(eq(USER), eq(new TotpSetupRequest("current-password")));
    }

    /** [AC-4] activate 端点：200 回一次性恢复码列表。 */
    @Test
    void activateReturnsOneTimeRecoveryCodes_ac4() throws Exception {
        when(totpAuthService.activate(eq(USER), eq(new TotpActivateRequest("12345678"))))
                .thenReturn(new TotpActivationView(List.of("10101010", "20202020")));

        mockMvc.perform(
                        post("/api/auth/2fa/activate")
                                .with(authentication(auth()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"code\":\"12345678\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.recoveryCodes.length()").value(2))
                .andExpect(jsonPath("$.data.recoveryCodes[0]").value("10101010"));
    }

    /** [AC-4] disable 端点：密码+动态码双确认透传，200 固定提示。 */
    @Test
    void disableForwardsDoubleConfirm_ac4() throws Exception {
        doNothing()
                .when(totpAuthService)
                .disable(eq(USER), eq(new TotpDisableRequest("pw", "12345678")));

        mockMvc.perform(
                        post("/api/auth/2fa/disable")
                                .with(authentication(auth()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"password\":\"pw\",\"code\":\"12345678\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.message").value("两步验证已关闭"));
    }

    /** [AC-4] 参数校验：验证码非 8 位数字 / 密码空白 → 40000 信封，不触达 Service。 */
    @Test
    void validationRejectsBadCodeAndBlankPassword_ac4() throws Exception {
        mockMvc.perform(
                        post("/api/auth/2fa/activate")
                                .with(authentication(auth()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"code\":\"1234567\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));
        mockMvc.perform(
                        post("/api/auth/2fa/setup")
                                .with(authentication(auth()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));
        mockMvc.perform(
                        post("/api/auth/2fa/disable")
                                .with(authentication(auth()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"password\":\"x\",\"code\":\"1234567a\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(totpAuthService);
    }

    /** [AC-4] 业务失败透传：setup 密码错 40101；activate 未 setup 40000。 */
    @Test
    void businessFailuresMapToEnvelope_ac4() throws Exception {
        when(totpAuthService.setup(eq(USER), eq(new TotpSetupRequest("wrong"))))
                .thenThrow(new BusinessException(ApiCode.AUTH_INVALID_CREDENTIALS, "当前密码不正确"));
        when(totpAuthService.activate(eq(USER), eq(new TotpActivateRequest("12345678"))))
                .thenThrow(new BusinessException(ApiCode.VALIDATION_FAILED, "请先完成两步验证注册（setup）"));

        mockMvc.perform(
                        post("/api/auth/2fa/setup")
                                .with(authentication(auth()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_INVALID_CREDENTIALS.code()))
                .andExpect(jsonPath("$.message").value("当前密码不正确"));
        mockMvc.perform(
                        post("/api/auth/2fa/activate")
                                .with(authentication(auth()))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"code\":\"12345678\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));
    }

    /** [AC-4] 限流注解契约（反射锁定，防重构摘除）：三个写端点账号 5/60s；status 读端点不挂限流。 */
    @Test
    void rateLimitAnnotationContract_ac4() throws Exception {
        for (String method : new String[] {"setup", "activate", "disable"}) {
            RateLimit rateLimit = rateLimitOf(method);
            assertThat(rateLimit).as("端点 %s 必须挂 @RateLimit", method).isNotNull();
            assertThat(rateLimit.dimensions()).containsExactly(RateLimitDimension.ACCOUNT);
            assertThat(rateLimit.limit()).isEqualTo(5);
            assertThat(rateLimit.windowSeconds()).isEqualTo(60);
            assertThat(rateLimit.accountExpression()).isEqualTo("#principal.id()");
        }
        Method statusMethod =
                TotpController.class.getDeclaredMethod("status", YilianUserPrincipal.class);
        assertThat(statusMethod.getAnnotation(RateLimit.class)).isNull();
    }

    /**
     * 反射读取 Controller 方法上的限流注解。
     *
     * @param methodName 方法名（setup/activate/disable 均为「请求体 + principal」双参数）
     * @return 注解；未标注时为 null
     */
    private static RateLimit rateLimitOf(String methodName) throws Exception {
        Method method = TotpController.class.getDeclaredMethod(methodName, parameterOf(methodName));
        return method.getAnnotation(RateLimit.class);
    }

    /**
     * 按方法名解析参数类型。
     *
     * @param methodName 方法名
     * @return 参数类型数组
     */
    private static Class<?>[] parameterOf(String methodName) {
        return switch (methodName) {
            case "setup" -> new Class<?>[] {TotpSetupRequest.class, YilianUserPrincipal.class};
            case "activate" ->
                    new Class<?>[] {TotpActivateRequest.class, YilianUserPrincipal.class};
            case "disable" -> new Class<?>[] {TotpDisableRequest.class, YilianUserPrincipal.class};
            default -> throw new IllegalArgumentException("未知方法: " + methodName);
        };
    }
}

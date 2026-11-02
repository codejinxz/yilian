package io.github.codejinxz.yilian.common.security;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.common.logging.ErrorLogRecorder;
import io.github.codejinxz.yilian.content.service.ContentQueryService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

/**
 * 安全响应头的 MVC 切片测试（013 AC-4）：加载真实 {@link SecurityConfig}， 对匿名放行的公开题库端点断言关键响应头。
 *
 * <p>CSP 资源来源白名单盘点记录在 {@code SecurityConfig} 常量注释中（自托管 + Google Fonts）。 测试环境未激活 local profile，走
 * prod 从紧 CSP；HSTS 由 Spring Security 只在 HTTPS 请求写入， 用 {@code secure(true)} 验证。
 */
@WebMvcTest(controllers = io.github.codejinxz.yilian.content.controller.BankController.class)
@Import({
    SecurityConfig.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    PasswordChangeRequiredInterceptor.class
})
class SecurityHeadersTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ContentQueryService contentQueryService;

    @MockitoBean private JwtDecoder jwtDecoder;

    @MockitoBean private ErrorLogRecorder errorLogRecorder;

    /** [AC-4] 任意 API 响应带齐安全头：nosniff / DENY / Referrer-Policy / prod CSP。 */
    @Test
    void securityHeadersPresentOnApiResponses_ac4() throws Exception {
        when(contentQueryService.listBanks(
                        isNull(), isNull(), isNull(), isNull(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(), 1, 20, 0));

        mockMvc.perform(get("/api/banks"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                // prod CSP 整段相等断言（以 SecurityConfig.CSP_PROD 常量为期望值逐字对齐）：
                // containsString("script-src 'self'") 区分不出 prod 从紧与 local 放宽——
                // 后者的 script-src 'self' 'unsafe-inline' 同样包含该前缀，「不含内联脚本来源」
                // 的从紧约束只有整段相等才真正被测到（字体来源白名单也随之覆盖）。
                .andExpect(
                        header().string(
                                        "Content-Security-Policy",
                                        equalTo(SecurityConfig.CSP_PROD)));
    }

    /** [AC-4] frame-ancestors 'none' 与 X-Frame-Options:DENY 双保险在位；未启用 HTTPS 时不写 HSTS。 */
    @Test
    void cspDeniesFramingAndHstsAbsentOnPlainHttp_ac4() throws Exception {
        when(contentQueryService.listBanks(
                        isNull(), isNull(), isNull(), isNull(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(), 1, 20, 0));

        mockMvc.perform(get("/api/banks"))
                .andExpect(status().isOk())
                .andExpect(
                        header().string(
                                        "Content-Security-Policy",
                                        containsString("frame-ancestors 'none'")))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    /** [AC-4] HSTS 只在 HTTPS 请求上写入，max-age 一年且含子域。 */
    @Test
    void hstsWrittenOnSecureRequests_ac4() throws Exception {
        when(contentQueryService.listBanks(
                        isNull(), isNull(), isNull(), isNull(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(), 1, 20, 0));

        mockMvc.perform(get("/api/banks").secure(true))
                .andExpect(status().isOk())
                // Spring Security 写出的格式在 max-age 与分号间带空格，这里只断言关键量。
                .andExpect(
                        header().string(
                                        "Strict-Transport-Security",
                                        containsString("max-age=31536000")))
                .andExpect(
                        header().string(
                                        "Strict-Transport-Security",
                                        containsString("includeSubDomains")));
    }

    /** [AC-4] 认证失败（401）响应同样带安全头：头过滤器在异常路径上也生效。 */
    @Test
    void securityHeadersPresentOnUnauthorizedResponses_ac4() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(jsonPath("$.code").value(40100));
    }
}

package io.github.codejinxz.yilian.admin.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.logging.ErrorLogRecorder;
import io.github.codejinxz.yilian.common.security.PasswordChangeRequiredInterceptor;
import io.github.codejinxz.yilian.common.security.RestAccessDeniedHandler;
import io.github.codejinxz.yilian.common.security.RestAuthenticationEntryPoint;
import io.github.codejinxz.yilian.common.security.SecurityConfig;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.system.service.UserAccountService;
import io.github.codejinxz.yilian.system.service.vo.InviteCodeAdminView;
import io.github.codejinxz.yilian.system.service.vo.InviteCodeCreatedView;

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
 * 邀请码两端点（API §8.1）的 HTTP 层切片测试 [AC-4]：POST 返回一次性明文与展示前缀、GET 只给前缀 （hash 不可逆不给明文），鉴权矩阵（匿名 401 / 非管理员
 * 403）[AC-1]；码的随机性与哈希落库由 {@code UserAccountServiceImplTest} 覆盖，此处 Service 用 Mockito 替身。
 */
@WebMvcTest(controllers = AdminInviteController.class)
@Import({
    SecurityConfig.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    PasswordChangeRequiredInterceptor.class
})
class AdminInviteControllerTest {

    private static final YilianUserPrincipal ADMIN =
            YilianUserPrincipal.of(1L, "admin", "ADMIN", true);

    private static final YilianUserPrincipal USER =
            YilianUserPrincipal.of(7L, "learner", "USER", true);

    @Autowired private MockMvc mockMvc;

    @MockitoBean private UserAccountService userAccountService;

    @MockitoBean private JwtDecoder jwtDecoder;

    @MockitoBean private ErrorLogRecorder errorLogRecorder;

    /** [AC-1] 匿名访问邀请码端点：401 信封（code=40100），不触达 Service。 */
    @Test
    void inviteEndpointsRejectAnonymousWith401_ac1() throws Exception {
        mockMvc.perform(get("/api/admin/invites"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        mockMvc.perform(post("/api/admin/invites").with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        verifyNoInteractions(userAccountService);
    }

    /** [AC-1] 非 ADMIN 登录用户访问邀请码端点：403 信封（code=40300），不触达 Service。 */
    @Test
    void inviteEndpointsRejectNonAdminWith403_ac1() throws Exception {
        mockMvc.perform(
                        get("/api/admin/invites")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));
        mockMvc.perform(
                        post("/api/admin/invites")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));
        verifyNoInteractions(userAccountService);
    }

    /** [AC-4] 管理员生成邀请码：200 信封返回一次性明文 code 与展示前缀 codePrefix，明文只在本次响应出现。 */
    @Test
    void adminCreatesInviteAndGetsOneTimePlaintext_ac4() throws Exception {
        when(userAccountService.adminCreateInvite(1L))
                .thenReturn(
                        new InviteCodeCreatedView(
                                "YL-AB2C-DE3F", "YL-AB2C", Instant.parse("2026-10-31T03:00:00Z")));

        mockMvc.perform(
                        post("/api/admin/invites")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.code").value("YL-AB2C-DE3F"))
                .andExpect(jsonPath("$.data.codePrefix").value("YL-AB2C"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-10-31T03:00:00Z"));

        verify(userAccountService).adminCreateInvite(1L);
    }

    /** [AC-4] 管理员查看邀请码列表：非分页 items 形状，只回 codePrefix/used/usedBy/createdAt，不含明文。 */
    @Test
    void adminListsInvitesWithPrefixOnly_ac4() throws Exception {
        when(userAccountService.adminListInvites())
                .thenReturn(
                        List.of(
                                new InviteCodeAdminView(
                                        "YL-AB2C",
                                        true,
                                        "learner",
                                        Instant.parse("2026-10-31T03:00:00Z")),
                                new InviteCodeAdminView(
                                        "YL-ZZ99",
                                        false,
                                        null,
                                        Instant.parse("2026-10-30T09:00:00Z"))));

        mockMvc.perform(
                        get("/api/admin/invites")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].codePrefix").value("YL-AB2C"))
                .andExpect(jsonPath("$.data.items[0].used").value(true))
                .andExpect(jsonPath("$.data.items[0].usedBy").value("learner"))
                .andExpect(jsonPath("$.data.items[0].createdAt").value("2026-10-31T03:00:00Z"))
                .andExpect(jsonPath("$.data.items[1].codePrefix").value("YL-ZZ99"))
                .andExpect(jsonPath("$.data.items[1].used").value(false))
                .andExpect(jsonPath("$.data.items[1].usedBy").isEmpty());
    }
}

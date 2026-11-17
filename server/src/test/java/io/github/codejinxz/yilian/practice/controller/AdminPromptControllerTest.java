package io.github.codejinxz.yilian.practice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.common.logging.ErrorLogRecorder;
import io.github.codejinxz.yilian.common.logging.OperationLog;
import io.github.codejinxz.yilian.common.security.PasswordChangeRequiredInterceptor;
import io.github.codejinxz.yilian.common.security.RestAccessDeniedHandler;
import io.github.codejinxz.yilian.common.security.RestAuthenticationEntryPoint;
import io.github.codejinxz.yilian.common.security.SecurityConfig;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.practice.service.PromptAdminService;
import io.github.codejinxz.yilian.practice.service.dto.PromptRegisterRequest;
import io.github.codejinxz.yilian.practice.service.vo.PromptVersionView;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

/**
 * 管理端 Prompt 版本治理端点（API §8.18）的 HTTP 层切片测试：管理员 200 与 PageResponse 形状、 筛选参数透传、登记请求校验（name
 * 白名单/content 非空与长度）、激活透传与幂等/冲突口径、鉴权矩阵 （匿名 401 / 非管理员 403）、审计上下文写入（PROMPT_REGISTER/PROMPT_ACTIVATE
 * 的动态对象 id）。 Service 内部分支（版本分配/降级/缓存失效）由集成测试覆盖。
 */
@WebMvcTest(controllers = AdminPromptController.class)
@Import({
    SecurityConfig.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    PasswordChangeRequiredInterceptor.class
})
class AdminPromptControllerTest {

    private static final YilianUserPrincipal ADMIN =
            YilianUserPrincipal.of(1L, "admin", "ADMIN", true);

    private static final YilianUserPrincipal USER =
            YilianUserPrincipal.of(7L, "learner", "USER", true);

    @Autowired private MockMvc mockMvc;

    @MockitoBean private PromptAdminService promptAdminService;

    @MockitoBean private JwtDecoder jwtDecoder;

    @MockitoBean private ErrorLogRecorder errorLogRecorder;

    /** [AC-1] 匿名检索：401 信封（code=40100），不触达 Service。 */
    @Test
    void listRejectsAnonymousWith401_ac1() throws Exception {
        mockMvc.perform(get("/api/admin/prompts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        verifyNoInteractions(promptAdminService);
    }

    /** [AC-1] 非 ADMIN 登录用户检索：403 信封（code=40300），不触达 Service。 */
    @Test
    void listRejectsNonAdminWith403_ac1() throws Exception {
        mockMvc.perform(
                        get("/api/admin/prompts")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));
        verifyNoInteractions(promptAdminService);
    }

    /** [AC-1] 管理员检索：筛选参数透传，200 信封为 PageResponse + PromptVersionView 形状（含 content 全文）。 */
    @Test
    void listForwardsFiltersAndReturnsPageShape_ac1() throws Exception {
        when(promptAdminService.list("review", "CANDIDATE", 2, 5))
                .thenReturn(PageResponse.of(List.of(promptView()), 2, 5, 1));

        mockMvc.perform(
                        get("/api/admin/prompts")
                                .queryParam("page", "2")
                                .queryParam("size", "5")
                                .queryParam("name", "review")
                                .queryParam("label", "CANDIDATE")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(5))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(11))
                .andExpect(jsonPath("$.data.items[0].name").value("review"))
                .andExpect(jsonPath("$.data.items[0].version").value(2))
                .andExpect(jsonPath("$.data.items[0].label").value("CANDIDATE"))
                .andExpect(jsonPath("$.data.items[0].content").value("复核提示词 v2 正文"))
                .andExpect(jsonPath("$.data.items[0].createdAt").value("2026-11-18T01:00:00Z"));

        org.mockito.Mockito.verify(promptAdminService).list("review", "CANDIDATE", 2, 5);
    }

    /** [AC-1] 非法筛选值：40000（服务端校验，不静默归全）。 */
    @Test
    void listRejectsIllegalFilter_ac1() throws Exception {
        when(promptAdminService.list("cloze", null, 1, 20))
                .thenThrow(
                        new BusinessException(
                                ApiCode.VALIDATION_FAILED, "name 只能为 ask、review 或 appeal"));

        mockMvc.perform(
                        get("/api/admin/prompts")
                                .queryParam("name", "cloze")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));
    }

    /** [AC-1] 匿名登记：401，不触达 Service。 */
    @Test
    void registerRejectsAnonymousWith401_ac1() throws Exception {
        mockMvc.perform(
                        post("/api/admin/prompts")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"review\", \"content\": \"v2\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(promptAdminService);
    }

    /** [AC-1] 非 ADMIN 登记：403，不触达 Service。 */
    @Test
    void registerRejectsNonAdminWith403_ac1() throws Exception {
        mockMvc.perform(
                        post("/api/admin/prompts")
                                .with(csrf())
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"review\", \"content\": \"v2\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(promptAdminService);
    }

    /** [AC-1] 登记成功：200 返回 CANDIDATE 行（版本号/标签由 Service 分配，controller 透传）。 */
    @Test
    void registerReturnsCandidateView_ac1() throws Exception {
        when(promptAdminService.register("review", "复核提示词 v2 正文", 1L)).thenReturn(promptView());

        mockMvc.perform(
                        post("/api/admin/prompts")
                                .with(csrf())
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"review\", \"content\": \"复核提示词 v2 正文\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.id").value(11))
                .andExpect(jsonPath("$.data.name").value("review"))
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.label").value("CANDIDATE"));

        org.mockito.Mockito.verify(promptAdminService).register("review", "复核提示词 v2 正文", 1L);
    }

    /** [AC-1] 登记参数校验：name 白名单、content 空白/超长（Bean Validation 40000/40001 信封）。 */
    @Test
    void registerValidatesBody_ac1() throws Exception {
        mockMvc.perform(
                        post("/api/admin/prompts")
                                .with(csrf())
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"cloze\", \"content\": \"任务路由模板\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(
                        post("/api/admin/prompts")
                                .with(csrf())
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\": \"review\", \"content\": \"  \"}"))
                .andExpect(status().isBadRequest());

        // 超长 content（20001 字符）：@Size 前置拦截，不触达 Service。
        String oversized = "x".repeat(20001);
        mockMvc.perform(
                        post("/api/admin/prompts")
                                .with(csrf())
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"name\": \"review\", \"content\": \""
                                                + oversized
                                                + "\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(promptAdminService);
    }

    /** [AC-1] 匿名激活：401，不触达 Service。 */
    @Test
    void activateRejectsAnonymousWith401_ac1() throws Exception {
        mockMvc.perform(post("/api/admin/prompts/11/activate").with(csrf()))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(promptAdminService);
    }

    /** [AC-1] 非 ADMIN 激活：403，不触达 Service。 */
    @Test
    void activateRejectsNonAdminWith403_ac1() throws Exception {
        mockMvc.perform(
                        post("/api/admin/prompts/11/activate")
                                .with(csrf())
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(promptAdminService);
    }

    /** [AC-1] 激活成功：200 返回 ACTIVE 行。 */
    @Test
    void activateReturnsActiveView_ac1() throws Exception {
        when(promptAdminService.activate(11L, 1L))
                .thenReturn(
                        new PromptVersionView(
                                11L,
                                "review",
                                2,
                                "ACTIVE",
                                "复核提示词 v2 正文",
                                Instant.parse("2026-11-18T01:00:00Z")));

        mockMvc.perform(
                        post("/api/admin/prompts/11/activate")
                                .with(csrf())
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.id").value(11))
                .andExpect(jsonPath("$.data.label").value("ACTIVE"));

        org.mockito.Mockito.verify(promptAdminService).activate(11L, 1L);
    }

    /** [AC-1] 审计口径：登记/激活标 @OperationLog(PROMPT_REGISTER/PROMPT_ACTIVATE)，GET 不标。 */
    @Test
    void writesCarryAuditAndReadDoesNot_ac1() throws Exception {
        OperationLog register =
                AdminPromptController.class
                        .getDeclaredMethod(
                                "register", PromptRegisterRequest.class, YilianUserPrincipal.class)
                        .getAnnotation(OperationLog.class);
        assertThat(register).as("登记必须带审计注解").isNotNull();
        assertThat(register.module()).isEqualTo("admin");
        assertThat(register.action()).isEqualTo("PROMPT_REGISTER");
        assertThat(register.targetType()).isEqualTo("prompt-version");

        OperationLog activate =
                AdminPromptController.class
                        .getDeclaredMethod("activate", long.class, YilianUserPrincipal.class)
                        .getAnnotation(OperationLog.class);
        assertThat(activate).as("激活必须带审计注解").isNotNull();
        assertThat(activate.module()).isEqualTo("admin");
        assertThat(activate.action()).isEqualTo("PROMPT_ACTIVATE");
        assertThat(activate.targetType()).isEqualTo("prompt-version");

        OperationLog list =
                AdminPromptController.class
                        .getDeclaredMethod("list", int.class, int.class, String.class, String.class)
                        .getAnnotation(OperationLog.class);
        assertThat(list).as("读接口不标审计").isNull();
    }

    /** [AC-1] 激活不存在/业务冲突：40400 / 40900 信封按 Service 异常透出。 */
    @Test
    void activateMapsServiceErrors_ac1() throws Exception {
        when(promptAdminService.activate(99L, 1L))
                .thenThrow(new BusinessException(ApiCode.NOT_FOUND));
        mockMvc.perform(
                        post("/api/admin/prompts/99/activate")
                                .with(csrf())
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiCode.NOT_FOUND.code()));

        when(promptAdminService.activate(12L, 1L))
                .thenThrow(new BusinessException(ApiCode.IDEMPOTENT_CONFLICT, "该版本已激活"));
        mockMvc.perform(
                        post("/api/admin/prompts/12/activate")
                                .with(csrf())
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ApiCode.IDEMPOTENT_CONFLICT.code()));
    }

    /**
     * 构造一行 CANDIDATE 视图夹具。
     *
     * @return 视图
     */
    private static PromptVersionView promptView() {
        return new PromptVersionView(
                11L,
                "review",
                2,
                "CANDIDATE",
                "复核提示词 v2 正文",
                Instant.parse("2026-11-18T01:00:00Z"));
    }
}

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
import io.github.codejinxz.yilian.common.security.PasswordChangeRequiredInterceptor;
import io.github.codejinxz.yilian.common.security.RestAccessDeniedHandler;
import io.github.codejinxz.yilian.common.security.RestAuthenticationEntryPoint;
import io.github.codejinxz.yilian.common.security.SecurityConfig;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.practice.service.AdminAppealService;
import io.github.codejinxz.yilian.practice.service.vo.AdminAppealView;

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
 * 管理端申诉终审端点（API §8.16）的 HTTP 层切片测试 [AC-3]：管理员 200 与 PageResponse 形状、 筛选参数透传、终审请求校验（status 枚举/reason
 * 必填）、非 PENDING 40900、鉴权矩阵（匿名 401 / 非管理员 403）；终审审计注解的存在性与操作日志上下文由切面测试与集成验证覆盖。
 */
@WebMvcTest(controllers = AdminAppealController.class)
@Import({
    SecurityConfig.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    PasswordChangeRequiredInterceptor.class
})
class AdminAppealControllerTest {

    private static final YilianUserPrincipal ADMIN =
            YilianUserPrincipal.of(1L, "admin", "ADMIN", true);

    private static final YilianUserPrincipal USER =
            YilianUserPrincipal.of(7L, "learner", "USER", true);

    @Autowired private MockMvc mockMvc;

    @MockitoBean private AdminAppealService adminAppealService;

    @MockitoBean private JwtDecoder jwtDecoder;

    @MockitoBean private ErrorLogRecorder errorLogRecorder;

    /** [AC-3] 匿名检索：401 信封（code=40100），不触达 Service。 */
    @Test
    void listRejectsAnonymousWith401_ac3() throws Exception {
        mockMvc.perform(get("/api/admin/appeals"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        verifyNoInteractions(adminAppealService);
    }

    /** [AC-3] 非 ADMIN 登录用户检索：403 信封（code=40300），不触达 Service。 */
    @Test
    void listRejectsNonAdminWith403_ac3() throws Exception {
        mockMvc.perform(
                        get("/api/admin/appeals")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));
        verifyNoInteractions(adminAppealService);
    }

    /** [AC-3] 管理员检索：筛选参数透传，200 信封为 PageResponse + AdminAppealView 形状（含 AI 意见与题目摘要）。 */
    @Test
    void listForwardsFiltersAndReturnsPageShape_ac3() throws Exception {
        when(adminAppealService.list("PENDING", "APPEAL", 2, 5))
                .thenReturn(PageResponse.of(List.of(appealView()), 2, 5, 1));

        mockMvc.perform(
                        get("/api/admin/appeals")
                                .queryParam("page", "2")
                                .queryParam("size", "5")
                                .queryParam("status", "PENDING")
                                .queryParam("kind", "APPEAL")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(5))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(60))
                .andExpect(jsonPath("$.data.items[0].attemptId").value("attempt-uuid-1"))
                .andExpect(jsonPath("$.data.items[0].annotationKey").value("slot:0"))
                .andExpect(jsonPath("$.data.items[0].kind").value("APPEAL"))
                .andExpect(jsonPath("$.data.items[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data.items[0].proposedAnswer").value("She is paying..."))
                .andExpect(jsonPath("$.data.items[0].reason").value("主语可换"))
                .andExpect(jsonPath("$.data.items[0].decisionVerdict").value("SUPPORT"))
                .andExpect(jsonPath("$.data.items[0].decisionReason").value("语义等价，可以接受"))
                .andExpect(jsonPath("$.data.items[0].decidedBy").value("LLM"))
                .andExpect(jsonPath("$.data.items[0].decidedAt").value("2026-11-06T02:00:00Z"))
                .andExpect(jsonPath("$.data.items[0].modelSnapshot").value("stub:stub-model"))
                .andExpect(jsonPath("$.data.items[0].promptVersionId").value(3))
                .andExpect(jsonPath("$.data.items[0].createdAt").value("2026-11-06T01:00:00Z"))
                .andExpect(jsonPath("$.data.items[0].sentenceZh").value("她越来越重视这件事情。"))
                .andExpect(jsonPath("$.data.items[0].username").value("it-appealer"));

        verifyShapeFiltersPassed();
    }

    private void verifyShapeFiltersPassed() {
        org.mockito.Mockito.verify(adminAppealService).list("PENDING", "APPEAL", 2, 5);
    }

    /** [AC-3] 非法筛选值：40000（服务端校验，不静默归全）。 */
    @Test
    void listRejectsIllegalFilter_ac3() throws Exception {
        when(adminAppealService.list("DRAFT", null, 1, 20))
                .thenThrow(new BusinessException(ApiCode.VALIDATION_FAILED, "status 取值不合法"));

        mockMvc.perform(
                        get("/api/admin/appeals")
                                .queryParam("status", "DRAFT")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));
    }

    /** [AC-3] 匿名终审：401，不触达 Service。 */
    @Test
    void decideRejectsAnonymousWith401_ac3() throws Exception {
        mockMvc.perform(
                        post("/api/admin/appeals/60/decision")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\": \"ACCEPTED\", \"reason\": \"同意\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(adminAppealService);
    }

    /** [AC-3] 非 ADMIN 终审：403，不触达 Service。 */
    @Test
    void decideRejectsNonAdminWith403_ac3() throws Exception {
        mockMvc.perform(
                        post("/api/admin/appeals/60/decision")
                                .with(csrf())
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\": \"ACCEPTED\", \"reason\": \"同意\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(adminAppealService);
    }

    /** [AC-3] 终审成功：200 返回终审后的行；请求体透传（status/ reason）。 */
    @Test
    void decideReturnsUpdatedView_ac3() throws Exception {
        when(adminAppealService.decide(60L, "ACCEPTED", "语义等价，收进可接受答案", 1L))
                .thenReturn(acceptedView());

        mockMvc.perform(
                        post("/api/admin/appeals/60/decision")
                                .with(csrf())
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"status\": \"ACCEPTED\","
                                                + " \"reason\": \"语义等价，收进可接受答案\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.id").value(60))
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.decidedBy").value("ADMIN"))
                .andExpect(jsonPath("$.data.decisionReason").value("语义等价，收进可接受答案"))
                .andExpect(jsonPath("$.data.decidedAt").value("2026-11-06T03:00:00Z"));

        org.mockito.Mockito.verify(adminAppealService).decide(60L, "ACCEPTED", "语义等价，收进可接受答案", 1L);
    }

    /** [AC-3] 终审参数校验：reason 必填、status 枚举（Bean Validation 40001/40000 信封）。 */
    @Test
    void decideValidatesBody_ac3() throws Exception {
        mockMvc.perform(
                        post("/api/admin/appeals/60/decision")
                                .with(csrf())
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\": \"ACCEPTED\", \"reason\": \"\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(
                        post("/api/admin/appeals/60/decision")
                                .with(csrf())
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\": \"MAYBE\", \"reason\": \"骑墙\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(adminAppealService);
    }

    /** [AC-3] 非 PENDING 终审：服务层 40900 信封透传。 */
    @Test
    void decideRejectsAlreadyDecidedWith40900_ac3() throws Exception {
        when(adminAppealService.decide(61L, "REJECTED", "与参考语义相反", 1L))
                .thenThrow(new BusinessException(ApiCode.IDEMPOTENT_CONFLICT, "该申诉已裁决，请刷新后查看"));

        mockMvc.perform(
                        post("/api/admin/appeals/61/decision")
                                .with(csrf())
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\": \"REJECTED\", \"reason\": \"与参考语义相反\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ApiCode.IDEMPOTENT_CONFLICT.code()));
    }

    /** [AC-3] 终审审计注解：@OperationLog(module=admin, action=APPEAL_DECIDE) 必须存在（审计红线）。 */
    @Test
    void decideCarriesOperationLogAnnotation_ac3() throws Exception {
        var annotation =
                org.springframework.core.annotation.AnnotatedElementUtils.findMergedAnnotation(
                        AdminAppealController.class.getDeclaredMethod(
                                "decide",
                                long.class,
                                io.github.codejinxz.yilian.practice.service.dto
                                        .AdminAppealDecisionRequest.class,
                                io.github.codejinxz.yilian.common.security.YilianUserPrincipal
                                        .class),
                        io.github.codejinxz.yilian.common.logging.OperationLog.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.module()).isEqualTo("admin");
        assertThat(annotation.action()).isEqualTo("APPEAL_DECIDE");
        assertThat(annotation.targetType()).isEqualTo("appeal");
    }

    /**
     * 构造申诉列表视图样例（PENDING + AI 意见）。
     *
     * @return 视图
     */
    private static AdminAppealView appealView() {
        return new AdminAppealView(
                60L,
                "attempt-uuid-1",
                "slot:0",
                "01HXXXXXXSENTENCE0",
                0,
                "APPEAL",
                "PENDING",
                "She is paying...",
                "主语可换",
                "SUPPORT",
                "语义等价，可以接受",
                "LLM",
                Instant.parse("2026-11-06T02:00:00Z"),
                "stub:stub-model",
                3L,
                Instant.parse("2026-11-06T01:00:00Z"),
                "她越来越重视这件事情。",
                "it-appealer");
    }

    /**
     * 构造终审后的视图样例（ACCEPTED + ADMIN）。
     *
     * @return 视图
     */
    private static AdminAppealView acceptedView() {
        return new AdminAppealView(
                60L,
                "attempt-uuid-1",
                "slot:0",
                "01HXXXXXXSENTENCE0",
                0,
                "APPEAL",
                "ACCEPTED",
                "She is paying...",
                "主语可换",
                "SUPPORT",
                "语义等价，收进可接受答案",
                "ADMIN",
                Instant.parse("2026-11-06T03:00:00Z"),
                "stub:stub-model",
                3L,
                Instant.parse("2026-11-06T01:00:00Z"),
                "她越来越重视这件事情。",
                "it-appealer");
    }
}

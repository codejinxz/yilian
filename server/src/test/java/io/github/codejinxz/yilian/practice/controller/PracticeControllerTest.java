package io.github.codejinxz.yilian.practice.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.logging.ErrorLogRecorder;
import io.github.codejinxz.yilian.common.ratelimit.RateLimit;
import io.github.codejinxz.yilian.common.security.PasswordChangeRequiredInterceptor;
import io.github.codejinxz.yilian.common.security.RestAccessDeniedHandler;
import io.github.codejinxz.yilian.common.security.RestAuthenticationEntryPoint;
import io.github.codejinxz.yilian.common.security.SecurityConfig;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.practice.grading.GradingResult;
import io.github.codejinxz.yilian.practice.service.PracticeService;
import io.github.codejinxz.yilian.practice.service.dto.AttemptCommand;
import io.github.codejinxz.yilian.practice.service.vo.AppealView;
import io.github.codejinxz.yilian.practice.service.vo.AttemptView;
import io.github.codejinxz.yilian.practice.service.vo.TodayView;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

/** 练习接口的 MVC 切片测试：真实 {@link SecurityConfig} 验证鉴权边界与信封结构， Service 用 Mockito 替身；覆盖限流注解口径（60/min）。 */
@WebMvcTest(controllers = PracticeController.class)
@Import({
    SecurityConfig.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    PasswordChangeRequiredInterceptor.class
})
class PracticeControllerTest {

    private static final YilianUserPrincipal USER =
            YilianUserPrincipal.of(7L, "learner", "USER", true);

    @Autowired private MockMvc mockMvc;

    @MockitoBean private PracticeService practiceService;

    @MockitoBean private JwtDecoder jwtDecoder;

    @MockitoBean private ErrorLogRecorder errorLogRecorder;

    /** 匿名访问返回 401 信封（code=40100），不放行练习域任何端点。 */
    @Test
    void anonymousIsRejectedWith401() throws Exception {
        mockMvc.perform(get("/api/practice/today"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
    }

    /** today 返回统一信封；data 直接承载启动台数字。 */
    @Test
    void todayReturnsEnvelope() throws Exception {
        when(practiceService.today(7L))
                .thenReturn(
                        new TodayView(LocalDate.of(2026, 10, 31), "Asia/Shanghai", 3, 1, 3, 50, 4));
        mockMvc.perform(
                        get("/api/practice/today")
                                .with(
                                        SecurityMockMvcRequestPostProcessors.authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.dueCount").value(3))
                .andExpect(jsonPath("$.data.timezone").value("Asia/Shanghai"));
    }

    /** 提交成功：200 信封；Service 收到请求体并返回 attemptId。 */
    @Test
    void submitAttemptDelegatesToService() throws Exception {
        when(practiceService.submitAttempt(eq(7L), any(AttemptCommand.class)))
                .thenReturn(
                        new AttemptView(
                                "3f0e8e50-6b1d-4e0f-9d3a-5f2c1b9e0001",
                                "01JSENTENCE",
                                "1001",
                                "CLOZE",
                                "FIRST",
                                80,
                                100,
                                new GradingResult(1, 80, 100, 4, 5, List.of(), "还差 1 处"),
                                null,
                                null,
                                null,
                                null,
                                false));
        mockMvc.perform(
                        post("/api/practice/attempts")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "attemptId": "3f0e8e50-6b1d-4e0f-9d3a-5f2c1b9e0001",
                                          "sentenceId": "01JSENTENCE",
                                          "sentenceVersionId": "1001",
                                          "mode": "CLOZE",
                                          "kind": "FIRST",
                                          "answer": {"schemaVersion": 1, "slots": []}
                                        }
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(
                        jsonPath("$.data.attemptId").value("3f0e8e50-6b1d-4e0f-9d3a-5f2c1b9e0001"))
                .andExpect(jsonPath("$.data.score").value(80));
    }

    /** 幂等冲突：Service 抛 40903 → HTTP 409 与业务码透传。 */
    @Test
    void submitAttemptConflictMapsTo409() throws Exception {
        when(practiceService.submitAttempt(eq(7L), any(AttemptCommand.class)))
                .thenThrow(new BusinessException(ApiCode.ATTEMPT_ID_REUSED));
        mockMvc.perform(
                        post("/api/practice/attempts")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {
                                          "attemptId": "3f0e8e50-6b1d-4e0f-9d3a-5f2c1b9e0002",
                                          "sentenceId": "01JSENTENCE",
                                          "sentenceVersionId": "1001",
                                          "mode": "CLOZE",
                                          "kind": "FIRST",
                                          "answer": {"schemaVersion": 1}
                                        }
                                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ApiCode.ATTEMPT_ID_REUSED.code()));
    }

    /** dispute 成功：200 信封回执（ACCEPTED/DISPUTE/annotationKey），Service 收到请求体理由。 */
    @Test
    void submitDisputeReturnsReceipt() throws Exception {
        when(practiceService.submitDispute(eq(7L), eq("a1"), eq("slot:0"), eq("我认为也对")))
                .thenReturn(new AppealView(21L, "ACCEPTED", "DISPUTE", "slot:0"));
        mockMvc.perform(
                        post("/api/practice/attempts/a1/annotations/slot:0/dispute")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"reason": "我认为也对"}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.appealId").value(21))
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.kind").value("DISPUTE"))
                .andExpect(jsonPath("$.data.annotationKey").value("slot:0"));
    }

    /** appeal 成功：请求体可整体缺省（两项契约均未强制必填），Service 收到 null。 */
    @Test
    void submitAppealAllowsMissingBody() throws Exception {
        when(practiceService.submitAppeal(eq(7L), eq("a1"), eq("free:0"), isNull(), isNull()))
                .thenReturn(new AppealView(20L, "PENDING", "APPEAL", "free:0"));
        mockMvc.perform(
                        post("/api/practice/attempts/a1/annotations/free:0/appeal")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.kind").value("APPEAL"))
                .andExpect(jsonPath("$.data.annotationKey").value("free:0"));
        verify(practiceService).submitAppeal(eq(7L), eq("a1"), eq("free:0"), isNull(), isNull());
    }

    /** 申诉/不认同错误映射：40300 越权、40403 不存在、40000 批注不存在、40900 重复提交。 */
    @Test
    void appealAndDisputeBusinessErrorsMapToEnvelope() throws Exception {
        when(practiceService.submitDispute(eq(7L), eq("a1"), eq("slot:0"), any()))
                .thenThrow(new BusinessException(ApiCode.ACCESS_DENIED));
        mockMvc.perform(
                        post("/api/practice/attempts/a1/annotations/slot:0/dispute")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));

        when(practiceService.submitDispute(eq(7L), eq("a2"), eq("slot:0"), any()))
                .thenThrow(new BusinessException(ApiCode.ATTEMPT_NOT_FOUND));
        mockMvc.perform(
                        post("/api/practice/attempts/a2/annotations/slot:0/dispute")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiCode.ATTEMPT_NOT_FOUND.code()));

        when(practiceService.submitAppeal(eq(7L), eq("a3"), eq("slot:1"), any(), any()))
                .thenThrow(new BusinessException(ApiCode.VALIDATION_FAILED, "批注不存在"));
        mockMvc.perform(
                        post("/api/practice/attempts/a3/annotations/slot:1/appeal")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));

        when(practiceService.submitDispute(eq(7L), eq("a4"), eq("slot:0"), any()))
                .thenThrow(new BusinessException(ApiCode.IDEMPOTENT_CONFLICT));
        mockMvc.perform(
                        post("/api/practice/attempts/a4/annotations/slot:0/dispute")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ApiCode.IDEMPOTENT_CONFLICT.code()));
    }

    /** 字段超长：proposedAnswer>255 或 reason>500 → 40000，不触达 Service。 */
    @Test
    void appealRejectsOversizedFields() throws Exception {
        mockMvc.perform(
                        post("/api/practice/attempts/a1/annotations/slot:0/appeal")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"proposedAnswer\": \""
                                                + "x".repeat(256)
                                                + "\", \"reason\": \""
                                                + "y".repeat(501)
                                                + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));
        verifyNoInteractions(practiceService);
    }

    /** next 无可练题：data=null 且 HTTP 200（不是 404）。 */
    @Test
    void nextReturnsNullDataWhenEmpty() throws Exception {
        when(practiceService.next(eq(7L), isNull(), isNull())).thenReturn(null);
        mockMvc.perform(
                        get("/api/practice/next")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    }

    /** 分页参数越界：40004。 */
    @Test
    void wrongQuestionsRejectsOversizedPage() throws Exception {
        mockMvc.perform(
                        get("/api/practice/wrong-questions")
                                .param("size", "200")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.INVALID_PAGINATION.code()));
    }

    /** 提交限流口径：60 次/分钟、账号维度。 */
    @Test
    void submitAttemptRateLimitIsSixtyPerMinutePerUser() throws Exception {
        RateLimit rateLimit =
                PracticeController.class
                        .getDeclaredMethod(
                                "submitAttempt", AttemptCommand.class, YilianUserPrincipal.class)
                        .getAnnotation(RateLimit.class);
        org.assertj.core.api.Assertions.assertThat(rateLimit).isNotNull();
        org.assertj.core.api.Assertions.assertThat(rateLimit.scene()).isEqualTo("practice-attempt");
        org.assertj.core.api.Assertions.assertThat(rateLimit.limit()).isEqualTo(60);
        org.assertj.core.api.Assertions.assertThat(rateLimit.windowSeconds()).isEqualTo(60);
        org.assertj.core.api.Assertions.assertThat(rateLimit.dimensions())
                .containsExactly(
                        io.github.codejinxz.yilian.common.ratelimit.RateLimitDimension.ACCOUNT);
    }
}

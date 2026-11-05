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
import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.common.logging.ErrorLogRecorder;
import io.github.codejinxz.yilian.common.ratelimit.RateLimit;
import io.github.codejinxz.yilian.common.security.PasswordChangeRequiredInterceptor;
import io.github.codejinxz.yilian.common.security.RestAccessDeniedHandler;
import io.github.codejinxz.yilian.common.security.RestAuthenticationEntryPoint;
import io.github.codejinxz.yilian.common.security.SecurityConfig;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.content.service.vo.SentenceChunk;
import io.github.codejinxz.yilian.content.service.vo.SentenceDetail;
import io.github.codejinxz.yilian.practice.grading.GradingResult;
import io.github.codejinxz.yilian.practice.service.PracticeService;
import io.github.codejinxz.yilian.practice.service.dto.AttemptCommand;
import io.github.codejinxz.yilian.practice.service.dto.NextQuery;
import io.github.codejinxz.yilian.practice.service.vo.AppealView;
import io.github.codejinxz.yilian.practice.service.vo.AttemptView;
import io.github.codejinxz.yilian.practice.service.vo.ModelReviewView;
import io.github.codejinxz.yilian.practice.service.vo.NextPracticeView;
import io.github.codejinxz.yilian.practice.service.vo.OverloadView;
import io.github.codejinxz.yilian.practice.service.vo.PostponeView;
import io.github.codejinxz.yilian.practice.service.vo.ReviewCardView;
import io.github.codejinxz.yilian.practice.service.vo.TodayView;
import io.github.codejinxz.yilian.practice.service.vo.WrongQuestionView;

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

import java.time.Instant;
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
        when(practiceService.next(eq(7L), any(NextQuery.class))).thenReturn(null);
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

    /** 深页码（offset 溢出）与负页码：40004 拦在 Controller，Service 不触达、不静默回首页。 */
    @Test
    void wrongQuestionsRejectsDeepAndNegativePage_023ac2() throws Exception {
        mockMvc.perform(
                        get("/api/practice/wrong-questions")
                                .param("page", "10001")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.INVALID_PAGINATION.code()));
        mockMvc.perform(
                        get("/api/practice/wrong-questions")
                                .param("page", "-1")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.INVALID_PAGINATION.code()));
        verifyNoInteractions(practiceService);
    }

    /** 复习队列同样受页码上限约束：page=10001 → 40004，与 content/upload 口径一致。 */
    @Test
    void reviewsRejectsDeepPage_023ac2() throws Exception {
        mockMvc.perform(
                        get("/api/practice/reviews")
                                .param("page", "10001")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.INVALID_PAGINATION.code()));
        verifyNoInteractions(practiceService);
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

    /**
     * next 成功：bankId/excludeSentenceId 透传（归一化为 excludeIds 集合），mode 仅做取值校验不参与选题（Q1 一句一卡），信封承载
     * sentence/review/selection。
     */
    @Test
    void nextForwardsFiltersAndReturnsSelectionEnvelope() throws Exception {
        when(practiceService.next(
                        eq(7L), eq(new NextQuery(1L, null, null, false, List.of("01JSENTENCE")))))
                .thenReturn(
                        new NextPracticeView(
                                publishedSentence(), reviewCard(10L, "LEARNING"), "DUE"));
        mockMvc.perform(
                        get("/api/practice/next")
                                .queryParam("bankId", "1")
                                .queryParam("mode", "CLOZE")
                                .queryParam("excludeSentenceId", "01JSENTENCE")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.sentence.id").value("01JPUBLIS000000000000000000C"))
                .andExpect(jsonPath("$.data.sentence.zh").value("他越来越重视这件事。"))
                .andExpect(jsonPath("$.data.review.id").value(10))
                .andExpect(jsonPath("$.data.selection").value("DUE"));
        // mode 通过取值校验但不透传 Service（一句一卡下不参与选题）。
        verify(practiceService)
                .next(eq(7L), eq(new NextQuery(1L, null, null, false, List.of("01JSENTENCE"))));
    }

    /**
     * [AC-1] D7 四参数透传并静默归一：excludeIds 逗号串 trim/去空/去重并与 excludeSentenceId 合并，errors 只认 "1"，空白 tag
     * 归一为 null。
     */
    @Test
    void nextNormalizesD7ParamsSilently_ac1() throws Exception {
        when(practiceService.next(eq(7L), any(NextQuery.class)))
                .thenReturn(new NextPracticeView(publishedSentence(), null, "NEW"));
        mockMvc.perform(
                        get("/api/practice/next")
                                .queryParam("sentenceId", " 01SPECIFIED ")
                                .queryParam("tag", " 副词结构 ")
                                .queryParam("errors", "1")
                                .queryParam("excludeIds", " 01A , 01B ,,01A ")
                                .queryParam("excludeSentenceId", "01B")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.selection").value("NEW"));
        // 归一化结果：sentenceId/tag 去空白；excludeIds=[01B,01A]（excludeSentenceId 先入集、重复与空段丢弃）；
        // errors=1 → true。与本地引擎 handleLocalRequest 的归一口径一致。
        verify(practiceService)
                .next(
                        eq(7L),
                        eq(
                                new NextQuery(
                                        null, "01SPECIFIED", "副词结构", true, List.of("01B", "01A"))));
    }

    /** [AC-1] D7 errors 非 "1" 静默归一为 false：不返回 400，与本地引擎 params.get("errors")==="1" 同口径。 */
    @Test
    void nextTreatsErrorsOtherThanOneAsFalse_ac1() throws Exception {
        when(practiceService.next(eq(7L), any(NextQuery.class)))
                .thenReturn(new NextPracticeView(publishedSentence(), null, "NEW"));
        mockMvc.perform(
                        get("/api/practice/next")
                                .queryParam("errors", "0")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isOk());
        mockMvc.perform(
                        get("/api/practice/next")
                                .queryParam("errors", "true")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isOk());
        verify(practiceService, org.mockito.Mockito.times(2))
                .next(eq(7L), eq(new NextQuery(null, null, null, false, List.of())));
    }

    /** next 的 mode 非法取值：40000 拦在 Controller，不触达 Service。 */
    @Test
    void nextRejectsInvalidMode() throws Exception {
        mockMvc.perform(
                        get("/api/practice/next")
                                .queryParam("mode", "SPAM")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));
        verifyNoInteractions(practiceService);
    }

    /** 作答详情成功与 404 映射：200 信封同提交响应同构；40403 答题记录不存在透传为 404。 */
    @Test
    void getAttemptReturnsDetailAndMapsNotFound() throws Exception {
        when(practiceService.getAttempt(7L, "a1"))
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
                        get("/api/practice/attempts/{attemptId}", "a1")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(
                        jsonPath("$.data.attemptId").value("3f0e8e50-6b1d-4e0f-9d3a-5f2c1b9e0001"))
                .andExpect(jsonPath("$.data.score").value(80))
                .andExpect(jsonPath("$.data.maxScore").value(100))
                .andExpect(jsonPath("$.data.result.summary").value("还差 1 处"));

        when(practiceService.getAttempt(7L, "a2"))
                .thenThrow(new BusinessException(ApiCode.ATTEMPT_NOT_FOUND));
        mockMvc.perform(
                        get("/api/practice/attempts/{attemptId}", "a2")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiCode.ATTEMPT_NOT_FOUND.code()));
    }

    /** 错题列表成功：PageResponse 信封，批注带 disputed 覆盖层与最近分数。 */
    @Test
    void wrongQuestionsReturnsPagedEnvelope() throws Exception {
        when(practiceService.wrongQuestions(7L, 1, 20, false, null, null))
                .thenReturn(
                        PageResponse.of(
                                List.of(
                                        new WrongQuestionView(
                                                "01JSENTENCE",
                                                "CLOZE",
                                                "LEARNING",
                                                Instant.parse("2026-10-25T00:00:00Z"),
                                                80,
                                                "3f0e8e50-6b1d-4e0f-9d3a-5f2c1b9e0001",
                                                2,
                                                List.of("搭配"),
                                                "他越来越重视这件事。",
                                                List.of(
                                                        new WrongQuestionView.WrongAnnotationView(
                                                                "slot:2", "搭配", "搭配错误", false)))),
                                1,
                                20,
                                1));
        mockMvc.perform(
                        get("/api/practice/wrong-questions")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].sentenceId").value("01JSENTENCE"))
                .andExpect(jsonPath("$.data.items[0].state").value("LEARNING"))
                .andExpect(jsonPath("$.data.items[0].lastScore").value(80))
                .andExpect(jsonPath("$.data.items[0].wrongCount").value(2))
                .andExpect(jsonPath("$.data.items[0].annotations[0].key").value("slot:2"))
                .andExpect(jsonPath("$.data.items[0].annotations[0].disputed").value(false))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        verify(practiceService).wrongQuestions(7L, 1, 20, false, null, null);
    }

    /** 复习队列成功：过滤参数透传，PageResponse 信封承载三档状态与 FSRS 投影。 */
    @Test
    void reviewsReturnsPagedEnvelope() throws Exception {
        when(practiceService.reviews(7L, 1, 20, "LEARNING", "CLOZE", true))
                .thenReturn(PageResponse.of(List.of(reviewCard(10L, "LEARNING")), 1, 20, 1));
        mockMvc.perform(
                        get("/api/practice/reviews")
                                .queryParam("state", "LEARNING")
                                .queryParam("mode", "CLOZE")
                                .queryParam("due", "true")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].id").value(10))
                .andExpect(jsonPath("$.data.items[0].state").value("LEARNING"))
                .andExpect(jsonPath("$.data.items[0].fsrsState").value("LEARNING"))
                .andExpect(jsonPath("$.data.items[0].reps").value(2))
                .andExpect(jsonPath("$.data.total").value(1));
        verify(practiceService).reviews(7L, 1, 20, "LEARNING", "CLOZE", true);
    }

    /** 复习队列 mode 非法取值：40000 拦在 Controller，不触达 Service。 */
    @Test
    void reviewsRejectsInvalidMode() throws Exception {
        mockMvc.perform(
                        get("/api/practice/reviews")
                                .queryParam("mode", "SPAM")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));
        verifyNoInteractions(practiceService);
    }

    /** 重置复习卡成功与 404 映射：reason 透传、信封回 NEW 卡；40404 不存在透传为 404。 */
    @Test
    void resetReviewReturnsCardAndMapsNotFound() throws Exception {
        when(practiceService.resetReview(7L, 10L, "想从头练")).thenReturn(reviewCard(10L, "NEW"));
        mockMvc.perform(
                        post("/api/practice/reviews/{reviewId}/reset", 10)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"想从头练\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(10))
                .andExpect(jsonPath("$.data.state").value("NEW"));

        when(practiceService.resetReview(7L, 99L, null))
                .thenThrow(new BusinessException(ApiCode.REVIEW_NOT_FOUND));
        mockMvc.perform(
                        post("/api/practice/reviews/{reviewId}/reset", 99)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiCode.REVIEW_NOT_FOUND.code()));
    }

    /** 降载状态：信封承载 dueCount/threshold/pushLimit/overload 四字段。 */
    @Test
    void overloadReturnsEnvelope() throws Exception {
        when(practiceService.overload(7L)).thenReturn(new OverloadView(9, 20, 5, true));
        mockMvc.perform(
                        get("/api/practice/reviews/overload")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.dueCount").value(9))
                .andExpect(jsonPath("$.data.threshold").value(20))
                .andExpect(jsonPath("$.data.pushLimit").value(5))
                .andExpect(jsonPath("$.data.overload").value(true));
    }

    /** 顺延：reason 透传；请求体缺省时 Service 收到 null；信封回 postponed/kept 计数。 */
    @Test
    void postponeForwardsOptionalReasonAndReturnsCounts() throws Exception {
        when(practiceService.postpone(7L, "今天没时间")).thenReturn(new PostponeView(4, 5));
        mockMvc.perform(
                        post("/api/practice/reviews/postpone")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"今天没时间\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.postponed").value(4))
                .andExpect(jsonPath("$.data.kept").value(5));

        when(practiceService.postpone(7L, null)).thenReturn(new PostponeView(0, 0));
        mockMvc.perform(
                        post("/api/practice/reviews/postpone")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.postponed").value(0));
        verify(practiceService).postpone(7L, null);
    }

    /** [AC-3] 匿名请求模型复核：401 信封（code=40100），不触达 Service。 */
    @Test
    void reviewRejectsAnonymousWith401_ac3() throws Exception {
        mockMvc.perform(
                        post("/api/practice/attempts/{attemptId}/review", "3f0e8e50-0001")
                                .with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        verifyNoInteractions(practiceService);
    }

    /** [AC-1] 复核委托：POST 无请求体，Service 返回复核视图并按统一信封回显新分与理由。 */
    @Test
    void reviewDelegatesToService_ac1() throws Exception {
        when(practiceService.reviewAttempt(7L, "3f0e8e50-0001"))
                .thenReturn(
                        new ModelReviewView(
                                88,
                                "整体可懂，介词搭配需要调整",
                                "stub:glm-4.7",
                                640L,
                                Instant.parse("2026-11-06T12:00:00Z"),
                                2,
                                61));
        mockMvc.perform(
                        post("/api/practice/attempts/{attemptId}/review", "3f0e8e50-0001")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.score").value(88))
                .andExpect(jsonPath("$.data.reason").value("整体可懂，介词搭配需要调整"))
                .andExpect(jsonPath("$.data.modelSnapshot").value("stub:glm-4.7"))
                .andExpect(jsonPath("$.data.latencyMs").value(640))
                .andExpect(jsonPath("$.data.sequence").value(2))
                .andExpect(jsonPath("$.data.originalScore").value(61));
        verify(practiceService).reviewAttempt(7L, "3f0e8e50-0001");
    }

    /** [AC-6] 复核限流注解契约：按账号 5 次/60 秒（成本护栏），场景名不含用户输入。 */
    @Test
    void reviewHasAccountRateLimitOfFivePerMinute_ac6() throws Exception {
        RateLimit rateLimit =
                PracticeController.class
                        .getDeclaredMethod("reviewAttempt", String.class, YilianUserPrincipal.class)
                        .getAnnotation(RateLimit.class);
        org.assertj.core.api.Assertions.assertThat(rateLimit).isNotNull();
        org.assertj.core.api.Assertions.assertThat(rateLimit.scene()).isEqualTo("model-review");
        org.assertj.core.api.Assertions.assertThat(rateLimit.limit()).isEqualTo(5);
        org.assertj.core.api.Assertions.assertThat(rateLimit.windowSeconds()).isEqualTo(60);
        org.assertj.core.api.Assertions.assertThat(rateLimit.dimensions())
                .containsExactly(
                        io.github.codejinxz.yilian.common.ratelimit.RateLimitDimension.ACCOUNT);
    }

    /**
     * 构造一条已发布题目视图。
     *
     * @return 完整内容视图
     */
    private static SentenceDetail publishedSentence() {
        return new SentenceDetail(
                "01JPUBLIS000000000000000000C",
                "1",
                12,
                "1001",
                3,
                "PUBLISHED",
                false,
                "他越来越重视这件事。",
                "He is paying more and more attention to this.",
                "「越来越」用 more and more 或 increasingly。",
                List.of(new SentenceChunk(0, "他", List.of("He"), "代词", null)),
                List.of(1, 2),
                List.of("副词结构", "搭配"),
                "TEM8");
    }

    /**
     * 构造一张复习卡视图。
     *
     * @param id 卡 id
     * @param state 三档状态
     * @return 复习卡视图
     */
    private static ReviewCardView reviewCard(long id, String state) {
        return new ReviewCardView(
                id,
                "01JSENTENCE",
                "CLOZE",
                state,
                "LEARNING",
                Instant.parse("2026-10-25T00:00:00Z"),
                3.2,
                5.1,
                2,
                1,
                1,
                2,
                Instant.parse("2026-10-24T05:30:00Z"));
    }
}

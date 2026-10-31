package io.github.codejinxz.yilian.practice.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.logging.ErrorLogRecorder;
import io.github.codejinxz.yilian.common.security.PasswordChangeRequiredInterceptor;
import io.github.codejinxz.yilian.common.security.RestAccessDeniedHandler;
import io.github.codejinxz.yilian.common.security.RestAuthenticationEntryPoint;
import io.github.codejinxz.yilian.common.security.SecurityConfig;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.practice.service.PracticeService;
import io.github.codejinxz.yilian.practice.service.vo.AccuracyView;
import io.github.codejinxz.yilian.practice.service.vo.ErrorTagsView;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 统计接口（API §7）的 MVC 切片测试：真实 {@link SecurityConfig} 验证 USER 鉴权边界与统一信封， 聚合口径由 {@code
 * AnalyticsIntegrationTest} 负责，此处只锁 HTTP 层契约。
 */
@WebMvcTest(controllers = AnalyticsController.class)
@Import({
    SecurityConfig.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    PasswordChangeRequiredInterceptor.class
})
class AnalyticsControllerTest {

    private static final YilianUserPrincipal USER =
            YilianUserPrincipal.of(7L, "learner", "USER", true);

    @Autowired private MockMvc mockMvc;

    @MockitoBean private PracticeService practiceService;

    @MockitoBean private JwtDecoder jwtDecoder;

    @MockitoBean private ErrorLogRecorder errorLogRecorder;

    /** 匿名访问正确率接口返回 401 信封（code=40100），不放行统计域。 */
    @Test
    void anonymousAccuracyIsRejectedWith401() throws Exception {
        mockMvc.perform(get("/api/analytics/accuracy"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
    }

    /** 匿名访问考点分布接口返回 401 信封（code=40100）。 */
    @Test
    void anonymousErrorTagsIsRejectedWith401() throws Exception {
        mockMvc.perform(get("/api/analytics/error-tags"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
    }

    /** 正确率成功响应：200 信封承载 §7.1 全部字段（byMode/daily14/recent）。 */
    @Test
    void accuracyReturnsFullEnvelope() throws Exception {
        when(practiceService.accuracy(7L, null, null, null, null)).thenReturn(accuracyView());

        mockMvc.perform(
                        get("/api/analytics/accuracy")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.from").value("2026-10-18"))
                .andExpect(jsonPath("$.data.to").value("2026-10-31"))
                .andExpect(jsonPath("$.data.totalAttempts").value(100))
                .andExpect(jsonPath("$.data.correctRate").value(72.5))
                .andExpect(jsonPath("$.data.averageScore").value(81.3))
                .andExpect(jsonPath("$.data.byMode[0].mode").value("CLOZE"))
                .andExpect(jsonPath("$.data.byMode[0].attempts").value(80))
                .andExpect(jsonPath("$.data.byMode[0].correctRate").value(75.0))
                .andExpect(jsonPath("$.data.daily14[0].date").value("2026-10-31"))
                .andExpect(jsonPath("$.data.daily14[0].attempts").value(6))
                .andExpect(jsonPath("$.data.recent[0].attemptId").value("3f0e8e50-0001"))
                .andExpect(jsonPath("$.data.recent[0].score").value(80));

        verify(practiceService).accuracy(7L, null, null, null, null);
    }

    /** from/to/bankId/mode 查询参数按声明类型透传给 Service；mode 值不做白名单校验（统计聚合口径在服务层）。 */
    @Test
    void accuracyForwardsQueryParams() throws Exception {
        when(practiceService.accuracy(
                        7L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), 1L, "CLOZE"))
                .thenReturn(accuracyView());

        mockMvc.perform(
                        get("/api/analytics/accuracy")
                                .queryParam("from", "2026-10-01")
                                .queryParam("to", "2026-10-31")
                                .queryParam("bankId", "1")
                                .queryParam("mode", "CLOZE")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isOk());

        verify(practiceService)
                .accuracy(7L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), 1L, "CLOZE");
    }

    /** 日期参数格式非法（非 ISO yyyy-MM-dd）按 40000 收敛为统一信封，不落 500。 */
    @Test
    void accuracyRejectsMalformedDateWith400() throws Exception {
        mockMvc.perform(
                        get("/api/analytics/accuracy")
                                .queryParam("from", "31/10/2026")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));
    }

    /** 考点分布成功响应：200 信封承载 from/to 与按次数降序的 items（tag/count/ratio）。 */
    @Test
    void errorTagsReturnsEnvelope() throws Exception {
        when(practiceService.errorTags(7L, null, null))
                .thenReturn(
                        new ErrorTagsView(
                                LocalDate.of(2026, 10, 18),
                                LocalDate.of(2026, 10, 31),
                                List.of(
                                        new ErrorTagsView.TagCountView("搭配", 18, 42.9),
                                        new ErrorTagsView.TagCountView("副词结构", 12, 28.6))));

        mockMvc.perform(
                        get("/api/analytics/error-tags")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.from").value("2026-10-18"))
                .andExpect(jsonPath("$.data.to").value("2026-10-31"))
                .andExpect(jsonPath("$.data.items[0].tag").value("搭配"))
                .andExpect(jsonPath("$.data.items[0].count").value(18))
                .andExpect(jsonPath("$.data.items[0].ratio").value(42.9))
                .andExpect(jsonPath("$.data.items[1].tag").value("副词结构"));

        verify(practiceService).errorTags(7L, null, null);
    }

    /** 考点分布空结果契约：items 为空数组而不是 null（API §1.7 空值规则）。 */
    @Test
    void errorTagsEmptyItemsAreEmptyArray() throws Exception {
        when(practiceService.errorTags(7L, null, null))
                .thenReturn(
                        new ErrorTagsView(
                                LocalDate.of(2026, 10, 18), LocalDate.of(2026, 10, 31), List.of()));

        mockMvc.perform(
                        get("/api/analytics/error-tags")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    /**
     * 构造带完整扩展字段的正确率视图。
     *
     * @return 正确率统计视图
     */
    private static AccuracyView accuracyView() {
        return new AccuracyView(
                LocalDate.of(2026, 10, 18),
                LocalDate.of(2026, 10, 31),
                100,
                72.5,
                81.3,
                List.of(new AccuracyView.ModeAccuracyView("CLOZE", 80, 75.0)),
                List.of(new AccuracyView.DailyAccuracyView(LocalDate.of(2026, 10, 31), 6, 66.7)),
                List.of(
                        new AccuracyView.RecentAttemptView(
                                "3f0e8e50-0001",
                                "01JSENTENCE",
                                "CLOZE",
                                "FIRST",
                                80,
                                Instant.parse("2026-10-31T05:30:00Z"))));
    }
}

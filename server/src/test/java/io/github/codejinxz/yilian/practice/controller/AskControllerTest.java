package io.github.codejinxz.yilian.practice.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import io.github.codejinxz.yilian.practice.service.TutorService;
import io.github.codejinxz.yilian.practice.service.vo.TutorAskView;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 答疑端点（API §6.12）的 HTTP 层切片测试 [AC-4]：鉴权（匿名 401）、参数校验（40000）、三态透传
 * （40400/40304）、降级文案（50000）、信封形状与限流注解契约（5 次/分钟/账号）； 服务层分支由 {@code TutorServiceImplTest} 覆盖。
 */
@WebMvcTest(controllers = AskController.class)
@Import({
    SecurityConfig.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    PasswordChangeRequiredInterceptor.class
})
class AskControllerTest {

    private static final YilianUserPrincipal USER =
            YilianUserPrincipal.of(7L, "learner", "USER", true);

    @Autowired private MockMvc mockMvc;

    @MockitoBean private TutorService tutorService;

    @MockitoBean private JwtDecoder jwtDecoder;

    @MockitoBean private ErrorLogRecorder errorLogRecorder;

    /** [AC-4] 匿名访问：401 信封（code=40100），不触达 Service。 */
    @Test
    void askRejectsAnonymousWith401_ac4() throws Exception {
        mockMvc.perform(
                        post("/api/practice/{sentenceId}/ask", "01HXXXX")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"question\":\"为什么？\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        verifyNoInteractions(tutorService);
    }

    /** [AC-4] 登录用户成功：200 信封 data 为 {answer, disclaimer}，question 原样透传。 */
    @Test
    void askReturnsAnswerWithDisclaimer_ac4() throws Exception {
        when(tutorService.ask(7L, "01HXXXX", "这里介词为什么用 to？"))
                .thenReturn(new TutorAskView("先想搭配对象。", "AI 生成，可能有误"));

        mockMvc.perform(
                        post("/api/practice/{sentenceId}/ask", "01HXXXX")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"question\":\"这里介词为什么用 to？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.answer").value("先想搭配对象。"))
                .andExpect(jsonPath("$.data.disclaimer").value("AI 生成，可能有误"));
    }

    /** [AC-4] 参数校验：问题为空或超过 500 字符一律 40000，不触达 Service。 */
    @Test
    void askRejectsBlankOrOverlongQuestionWith40000_ac4() throws Exception {
        mockMvc.perform(
                        post("/api/practice/{sentenceId}/ask", "01HXXXX")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));

        mockMvc.perform(
                        post("/api/practice/{sentenceId}/ask", "01HXXXX")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"question\":\"" + "问".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));
        verifyNoInteractions(tutorService);
    }

    /** [AC-4] 三态/降级透传：未配置 40400、未开启 40304、模型失败 50000，Service 异常原样映射信封。 */
    @Test
    void askPassesThroughStateAndDegradeCodes_ac4() throws Exception {
        when(tutorService.ask(any(Long.class), eq("not-configured"), eq("问")))
                .thenThrow(new BusinessException(ApiCode.NOT_FOUND));
        when(tutorService.ask(any(Long.class), eq("closed"), eq("问")))
                .thenThrow(new BusinessException(ApiCode.ASK_NOT_OPEN));
        when(tutorService.ask(any(Long.class), eq("degraded"), eq("问")))
                .thenThrow(new BusinessException(ApiCode.INTERNAL_ERROR, "答疑暂时不可用，请稍后再试"));

        mockMvc.perform(
                        post("/api/practice/{sentenceId}/ask", "not-configured")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"question\":\"问\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiCode.NOT_FOUND.code()));
        mockMvc.perform(
                        post("/api/practice/{sentenceId}/ask", "closed")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"question\":\"问\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ASK_NOT_OPEN.code()))
                .andExpect(jsonPath("$.message").value("答疑未开放"));
        mockMvc.perform(
                        post("/api/practice/{sentenceId}/ask", "degraded")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"question\":\"问\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(ApiCode.INTERNAL_ERROR.code()))
                .andExpect(jsonPath("$.message").value("答疑暂时不可用，请稍后再试"));
    }

    /** [AC-4] 缺请求体：40001（请求体格式不正确），与 40000 区分。 */
    @Test
    void askRejectsMissingBodyWith40001_ac4() throws Exception {
        mockMvc.perform(
                        post("/api/practice/{sentenceId}/ask", "01HXXXX")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.MALFORMED_REQUEST.code()));
    }

    /** [AC-4] 限流注解契约：按账号 5 次/60 秒（成本护栏），场景名不含用户输入。 */
    @Test
    void askHasAccountRateLimitOfFivePerMinute_ac4() throws Exception {
        RateLimit rateLimit =
                AskController.class
                        .getDeclaredMethod(
                                "ask",
                                String.class,
                                io.github.codejinxz.yilian.practice.service.dto.AskQuestionRequest
                                        .class,
                                YilianUserPrincipal.class)
                        .getAnnotation(RateLimit.class);
        assertThat(rateLimit).isNotNull();
        assertThat(rateLimit.scene()).isEqualTo("tutor-ask");
        assertThat(rateLimit.dimensions()).containsExactly(RateLimitDimension.ACCOUNT);
        assertThat(rateLimit.limit()).isEqualTo(5);
        assertThat(rateLimit.windowSeconds()).isEqualTo(60);
    }
}

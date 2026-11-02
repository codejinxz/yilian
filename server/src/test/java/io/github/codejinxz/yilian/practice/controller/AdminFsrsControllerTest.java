package io.github.codejinxz.yilian.practice.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import io.github.codejinxz.yilian.common.logging.OperationLog;
import io.github.codejinxz.yilian.common.logging.OperationLogContext;
import io.github.codejinxz.yilian.common.security.PasswordChangeRequiredInterceptor;
import io.github.codejinxz.yilian.common.security.RestAccessDeniedHandler;
import io.github.codejinxz.yilian.common.security.RestAuthenticationEntryPoint;
import io.github.codejinxz.yilian.common.security.SecurityConfig;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.practice.service.FsrsAdminService;
import io.github.codejinxz.yilian.practice.service.vo.FsrsParameterVersionView;

import org.junit.jupiter.api.AfterEach;
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
import java.util.Map;

/**
 * 管理端 FSRS 端点（API §8.x）的 HTTP 层切片测试 [AC-3]：鉴权矩阵（匿名 401 / 非管理员 403）、 信封形状、样本不足 40000 与非候选启用 40000
 * 的透传、审计注解口径； 优化器数学与启用状态机分别由 {@code FsrsOptimizerTest} 与 {@code FsrsAdminIntegrationTest} 覆盖。
 */
@WebMvcTest(controllers = AdminFsrsController.class)
@Import({
    SecurityConfig.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    PasswordChangeRequiredInterceptor.class
})
class AdminFsrsControllerTest {

    private static final YilianUserPrincipal ADMIN =
            YilianUserPrincipal.of(1L, "admin", "ADMIN", true);

    private static final YilianUserPrincipal USER =
            YilianUserPrincipal.of(7L, "learner", "USER", true);

    @Autowired private MockMvc mockMvc;

    @MockitoBean private FsrsAdminService fsrsAdminService;

    @MockitoBean private JwtDecoder jwtDecoder;

    @MockitoBean private ErrorLogRecorder errorLogRecorder;

    @AfterEach
    void cleanOperationLogContext() {
        // 切面不在 @WebMvcTest 上下文里，测试线程上的审计覆盖值需要手动清，避免串测。
        OperationLogContext.clear();
    }

    /** [AC-3] 匿名访问：401 信封（code=40100），三个端点都不触达 Service。 */
    @Test
    void fsrsEndpointsRejectAnonymousWith401_ac3() throws Exception {
        mockMvc.perform(post("/api/admin/fsrs/optimize").with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        mockMvc.perform(get("/api/admin/fsrs/parameters"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        mockMvc.perform(post("/api/admin/fsrs/parameters/{id}/enable", 9).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        verifyNoInteractions(fsrsAdminService);
    }

    /** [AC-3] 非 ADMIN 登录用户：403 信封（code=40300），不触达 Service（服务端权限，不看前端隐藏）。 */
    @Test
    void fsrsEndpointsRejectNonAdminWith403_ac3() throws Exception {
        mockMvc.perform(
                        post("/api/admin/fsrs/optimize")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"userId\": 7}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));
        mockMvc.perform(
                        get("/api/admin/fsrs/parameters")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));
        mockMvc.perform(
                        post("/api/admin/fsrs/parameters/{id}/enable", 9)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));
        verifyNoInteractions(fsrsAdminService);
    }

    /** [AC-3] optimize 成功与样本不足 40000：body.userId 必填透传，业务异常信封透传。 */
    @Test
    void optimizeDelegatesAndMapsInsufficientSamples_ac3() throws Exception {
        when(fsrsAdminService.optimize(7L, 1L)).thenReturn(candidateView());
        mockMvc.perform(
                        post("/api/admin/fsrs/optimize")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"userId\": 7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(33))
                .andExpect(jsonPath("$.data.status").value("CANDIDATE"))
                .andExpect(jsonPath("$.data.sampleCount").value(1200))
                .andExpect(jsonPath("$.data.metrics.optimizer").value("java-sgd-m2.1"));
        verify(fsrsAdminService).optimize(7L, 1L);

        when(fsrsAdminService.optimize(8L, 1L))
                .thenThrow(new BusinessException(ApiCode.VALIDATION_FAILED, "复习样本不足 50 条"));
        mockMvc.perform(
                        post("/api/admin/fsrs/optimize")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"userId\": 8}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));
    }

    /**
     * [AC-3] optimize 请求体缺 userId / 空 JSON 体：40000/40001 拦在 Controller；无 Content-Type 按
     * 41500（consumes 约束）。
     */
    @Test
    void optimizeRejectsMissingUserIdAndMissingBody_ac3() throws Exception {
        mockMvc.perform(
                        post("/api/admin/fsrs/optimize")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));
        mockMvc.perform(
                        post("/api/admin/fsrs/optimize")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.MALFORMED_REQUEST.code()));
        mockMvc.perform(
                        post("/api/admin/fsrs/optimize")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(ApiCode.UNSUPPORTED_MEDIA_TYPE.code()));
        verifyNoInteractions(fsrsAdminService);
    }

    /** [AC-3] parameters 列表：信封承载版本数组（权重数组/训练摘要/状态）。 */
    @Test
    void parametersReturnsEnvelope_ac3() throws Exception {
        when(fsrsAdminService.listParameters()).thenReturn(List.of(candidateView(), seedView()));
        mockMvc.perform(
                        get("/api/admin/fsrs/parameters")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].status").value("CANDIDATE"))
                .andExpect(jsonPath("$.data[0].weights.length()").value(21))
                .andExpect(jsonPath("$.data[1].status").value("ACTIVE"))
                // 种子行是对象形 {"w":[18 维]}:视图如实输出 18 维 w 数组,不是 21 维(曾掩盖 D-2 根因)。
                .andExpect(jsonPath("$.data[1].weights.length()").value(18))
                .andExpect(jsonPath("$.data[1].metrics").doesNotExist());
        verify(fsrsAdminService).listParameters();
    }

    /** [AC-3] enable 成功、非候选 40000 与版本不存在 40400：业务异常信封透传。 */
    @Test
    void enableMapsStateAndNotFoundErrors_ac3() throws Exception {
        when(fsrsAdminService.enable(33L, 1L)).thenReturn(enabledView());
        mockMvc.perform(
                        post("/api/admin/fsrs/parameters/{id}/enable", 33)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(33))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        verify(fsrsAdminService).enable(33L, 1L);

        when(fsrsAdminService.enable(34L, 1L))
                .thenThrow(new BusinessException(ApiCode.VALIDATION_FAILED, "仅 CANDIDATE 状态可启用"));
        mockMvc.perform(
                        post("/api/admin/fsrs/parameters/{id}/enable", 34)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));

        when(fsrsAdminService.enable(99L, 1L))
                .thenThrow(new BusinessException(ApiCode.NOT_FOUND, "权重版本不存在"));
        mockMvc.perform(
                        post("/api/admin/fsrs/parameters/{id}/enable", 99)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiCode.NOT_FOUND.code()));
    }

    /** [AC-3] 审计口径：optimize 与 enable 标注 @OperationLog（FSRS_OPTIMIZE / FSRS_ENABLE），列表不标。 */
    @Test
    void writeEndpointsCarryOperationLogAudit_ac3() throws Exception {
        OperationLog optimize =
                AdminFsrsController.class
                        .getDeclaredMethod(
                                "optimize",
                                io.github.codejinxz.yilian.practice.service.dto.FsrsOptimizeRequest
                                        .class,
                                YilianUserPrincipal.class)
                        .getAnnotation(OperationLog.class);
        assertThat(optimize).isNotNull();
        assertThat(optimize.module()).isEqualTo("practice");
        assertThat(optimize.action()).isEqualTo("FSRS_OPTIMIZE");

        OperationLog enable =
                AdminFsrsController.class
                        .getDeclaredMethod("enable", Long.class, YilianUserPrincipal.class)
                        .getAnnotation(OperationLog.class);
        assertThat(enable).isNotNull();
        assertThat(enable.module()).isEqualTo("practice");
        assertThat(enable.action()).isEqualTo("FSRS_ENABLE");

        OperationLog list =
                AdminFsrsController.class
                        .getDeclaredMethod("parameters", YilianUserPrincipal.class)
                        .getAnnotation(OperationLog.class);
        assertThat(list).as("读接口默认不标操作日志").isNull();
    }

    /**
     * 候选版本视图样例。
     *
     * @return 视图
     */
    private static FsrsParameterVersionView candidateView() {
        return new FsrsParameterVersionView(
                33L,
                "FSRS-6",
                "GLOBAL",
                "CANDIDATE",
                List.of(
                        0.2, 1.1, 3.2, 16.1, 7.0, 0.5, 2.0, 0.007, 1.5, 0.1, 1.0, 1.8, 0.1, 0.3,
                        2.2, 0.2, 3.0, 0.7, 0.3, 0.1, 0.2),
                1200,
                Map.of(
                        "schemaVersion",
                        1,
                        "sampleCount",
                        1200,
                        "logLoss",
                        0.42,
                        "rmse",
                        0.08,
                        "optimizer",
                        "java-sgd-m2.1",
                        "optimizerVersion",
                        "1.0"),
                Instant.parse("2026-11-03T00:00:00Z"),
                Instant.parse("2026-11-03T00:00:00Z"),
                "SGD 优化目标用户 7 的复习日志");
    }

    /**
     * 启用后视图样例。
     *
     * @return 视图
     */
    private static FsrsParameterVersionView enabledView() {
        return new FsrsParameterVersionView(
                33L,
                "FSRS-6",
                "GLOBAL",
                "ACTIVE",
                candidateView().weights(),
                1200,
                candidateView().metrics(),
                candidateView().optimizedAt(),
                candidateView().createdAt(),
                candidateView().reason());
    }

    /**
     * V9 种子视图样例（metrics 为 null，验证响应里缺省）。
     *
     * @return 视图
     */
    private static FsrsParameterVersionView seedView() {
        return new FsrsParameterVersionView(
                1L,
                "FSRS-6",
                "GLOBAL",
                "ACTIVE",
                List.of(
                        0.4, 1.2, 2.4, 5.3, 3.2, 0.8, 0.6, 2.0, 1.5, 0.3, 1.0, 2.0, 40.0, 0.6, 3.0,
                        5.1, 0.9, 0.4),
                0,
                null,
                null,
                Instant.parse("2026-10-30T00:00:00Z"),
                "M2 原型占位权重");
    }
}

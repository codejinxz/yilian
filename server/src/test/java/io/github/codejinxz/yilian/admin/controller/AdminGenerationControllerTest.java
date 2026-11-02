package io.github.codejinxz.yilian.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.codejinxz.yilian.admin.service.AdminGenerationService;
import io.github.codejinxz.yilian.admin.service.dto.AdminGenerationImportItem;
import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.logging.ErrorLogRecorder;
import io.github.codejinxz.yilian.common.logging.OperationLogContext;
import io.github.codejinxz.yilian.common.security.PasswordChangeRequiredInterceptor;
import io.github.codejinxz.yilian.common.security.RestAccessDeniedHandler;
import io.github.codejinxz.yilian.common.security.RestAuthenticationEntryPoint;
import io.github.codejinxz.yilian.common.security.SecurityConfig;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.upload.dto.ImportBatchViewResponse;

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

import java.util.List;

/**
 * 出题导入端点（API §8.11）的 HTTP 层切片测试 [AC-3]：管理员 200 与批次视图形状、审计上下文、 鉴权矩阵（匿名 401 / 非管理员 403）；行校验与指纹去重由
 * {@code AdminGenerationServiceImplTest} 负责。
 */
@WebMvcTest(controllers = AdminGenerationController.class)
@Import({
    SecurityConfig.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    PasswordChangeRequiredInterceptor.class
})
class AdminGenerationControllerTest {

    private static final YilianUserPrincipal ADMIN =
            YilianUserPrincipal.of(1L, "admin", "ADMIN", true);

    private static final YilianUserPrincipal USER =
            YilianUserPrincipal.of(7L, "learner", "USER", true);

    /** 请求体样例：与前端 GEN_SAMPLE 校验通过行的形状一致（chunk 省略 index）。 */
    private static final String REQUEST_BODY =
            """
            {"bankId":"5","items":[{"zh":"他越来越胖。","ref":"He is getting fatter and fatter.",\
            "note":"主语 + 系表结构，进行时。","level":"CET-4","tags":["搭配"],\
            "chunks":[{"zh":"他","answers":["He"]},{"zh":"越来越胖","answers":["is getting fatter and fatter"]}],\
            "blank":[1]}],"reason":"AI 生成导入"}\
            """;

    @Autowired private MockMvc mockMvc;

    @MockitoBean private AdminGenerationService adminGenerationService;

    @MockitoBean private JwtDecoder jwtDecoder;

    @MockitoBean private ErrorLogRecorder errorLogRecorder;

    @AfterEach
    void cleanOperationLogContext() {
        // 切面不在 @WebMvcTest 上下文里，测试线程上的审计覆盖值需要手动清，避免串测。
        OperationLogContext.clear();
    }

    /** [AC-3] 匿名访问：401 信封，不触达 Service。 */
    @Test
    void importRejectsAnonymousWith401_ac3() throws Exception {
        mockMvc.perform(post("/api/admin/generation/import").with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        verifyNoInteractions(adminGenerationService);
    }

    /** [AC-3] 非 ADMIN 登录用户：403 信封，不触达 Service。 */
    @Test
    void importRejectsNonAdminWith403_ac3() throws Exception {
        mockMvc.perform(
                        post("/api/admin/generation/import")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));
        verifyNoInteractions(adminGenerationService);
    }

    /** [AC-3] 管理员导入：200 批次视图（COMMITTED），审计上下文带合成批次 id 与原因。 */
    @Test
    void importReturnsBatchViewAndWritesAuditContext_ac3() throws Exception {
        when(adminGenerationService.importGenerated(1L, "5", requestItems()))
                .thenReturn(batchView());

        mockMvc.perform(
                        post("/api/admin/generation/import")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(REQUEST_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.batchId").value("batch-1"))
                .andExpect(jsonPath("$.data.bankId").value(5))
                .andExpect(jsonPath("$.data.status").value("COMMITTED"))
                .andExpect(jsonPath("$.data.format").value("JSON"))
                .andExpect(jsonPath("$.data.originalFilename").value("AI 生成.json"))
                .andExpect(jsonPath("$.data.totalRows").value(2))
                .andExpect(jsonPath("$.data.validRows").value(2))
                .andExpect(jsonPath("$.data.invalidRows").value(0))
                .andExpect(jsonPath("$.data.importedRows").value(2))
                .andExpect(jsonPath("$.data.errorSummary").isEmpty());

        verify(adminGenerationService).importGenerated(1L, "5", requestItems());
        assertThat(OperationLogContext.current())
                .hasValueSatisfying(
                        override -> {
                            assertThat(override.targetType()).isEqualTo("import-batch");
                            assertThat(override.targetId()).isEqualTo("batch-1");
                            assertThat(override.reason()).isEqualTo("AI 生成导入");
                        });
    }

    /** [AC-3] 缺省审计原因：reason 缺省时上下文落「AI 生成导入」（与 mock 契约一致）。 */
    @Test
    void importWithoutReasonFallsBackToDefaultAuditReason_ac3() throws Exception {
        when(adminGenerationService.importGenerated(1L, "5", requestItems()))
                .thenReturn(batchView());

        mockMvc.perform(
                        post("/api/admin/generation/import")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(REQUEST_BODY.replace(",\"reason\":\"AI 生成导入\"", "")))
                .andExpect(status().isOk());

        assertThat(OperationLogContext.current())
                .hasValueSatisfying(override -> assertThat(override.reason()).isEqualTo("AI 生成导入"));
    }

    /** [AC-3] Service 抛 40401（题库不可导入）→ 404 信封原样透传。 */
    @Test
    void importMapsUnimportableBankTo40401_ac3() throws Exception {
        when(adminGenerationService.importGenerated(1L, "9", requestItems()))
                .thenThrow(new BusinessException(ApiCode.BANK_NOT_FOUND));

        mockMvc.perform(
                        post("/api/admin/generation/import")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(REQUEST_BODY.replace("\"5\"", "\"9\"")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiCode.BANK_NOT_FOUND.code()))
                .andExpect(jsonPath("$.message").value("题库不存在"));
    }

    /**
     * 请求行期望值（与 REQUEST_BODY 反序列化结果一致；chunk index 缺省为 null）。
     *
     * @return 行列表
     */
    private static List<AdminGenerationImportItem> requestItems() {
        return List.of(
                new AdminGenerationImportItem(
                        "他越来越胖。",
                        "He is getting fatter and fatter.",
                        "主语 + 系表结构，进行时。",
                        "CET-4",
                        List.of("搭配"),
                        List.of(
                                new AdminGenerationImportItem.Chunk(
                                        null, "他", List.of("He"), null, null),
                                new AdminGenerationImportItem.Chunk(
                                        null,
                                        "越来越胖",
                                        List.of("is getting fatter and fatter"),
                                        null,
                                        null)),
                        List.of(1)));
    }

    /**
     * 构造批次视图样例。
     *
     * @return 批次视图
     */
    private static ImportBatchViewResponse batchView() {
        return new ImportBatchViewResponse(
                "batch-1",
                5L,
                "COMMITTED",
                "JSON",
                "AI 生成.json",
                2,
                2,
                0,
                2,
                "2026-10-31T12:00:00Z",
                "2026-10-31T12:00:00Z",
                "2026-10-31T12:00:00Z",
                null);
    }
}

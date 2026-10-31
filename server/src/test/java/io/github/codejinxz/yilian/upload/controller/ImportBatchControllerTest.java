package io.github.codejinxz.yilian.upload.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import io.github.codejinxz.yilian.upload.dto.ImportBatchDetailResponse;
import io.github.codejinxz.yilian.upload.dto.ImportBatchViewResponse;
import io.github.codejinxz.yilian.upload.dto.ImportErrorSummary;
import io.github.codejinxz.yilian.upload.dto.ImportPreviewResponse;
import io.github.codejinxz.yilian.upload.dto.ImportRowError;
import io.github.codejinxz.yilian.upload.dto.ImportRowView;
import io.github.codejinxz.yilian.upload.service.ImportBatchService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 导入批次五端点（API §5.4–§5.8）的 HTTP 层切片测试：真实 {@link SecurityConfig} 锁定 {@code /api/admin/**} 的鉴权矩阵（匿名
 * 401 / 非管理员 403）、multipart 绑定、参数校验与 错误码到统一信封的映射；数据构造与状态机由 {@code
 * ImportBatchServiceIntegrationTest} 负责，此处 Service 全部用 Mockito 替身。
 */
@WebMvcTest(controllers = ImportBatchController.class)
@Import({
    SecurityConfig.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    PasswordChangeRequiredInterceptor.class
})
class ImportBatchControllerTest {

    private static final YilianUserPrincipal ADMIN =
            YilianUserPrincipal.of(1L, "admin", "ADMIN", true);

    private static final YilianUserPrincipal USER =
            YilianUserPrincipal.of(7L, "learner", "USER", true);

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ImportBatchService importBatchService;

    @MockitoBean private JwtDecoder jwtDecoder;

    @MockitoBean private ErrorLogRecorder errorLogRecorder;

    /** 匿名访问五个管理端点一律 401 信封（code=40100），不触达 Service。 */
    @Test
    void adminEndpointsRejectAnonymousWith401() throws Exception {
        mockMvc.perform(get("/api/admin/uploads"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        mockMvc.perform(get("/api/admin/uploads/{batchId}", "b1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        mockMvc.perform(multipart("/api/admin/uploads/preview").with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        mockMvc.perform(post("/api/admin/uploads/{batchId}/commit", "b1").with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        mockMvc.perform(
                        post("/api/admin/uploads/{batchId}/rollback", "b1")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"内容有误\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        verifyNoInteractions(importBatchService);
    }

    /** 非 ADMIN 登录用户访问 /api/admin/**：403 信封（code=40300），角色不足不触达 Service。 */
    @Test
    void nonAdminIsRejectedWith403() throws Exception {
        mockMvc.perform(
                        get("/api/admin/uploads")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));
        mockMvc.perform(
                        post("/api/admin/uploads/{batchId}/commit", "b1")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));
        verifyNoInteractions(importBatchService);
    }

    /** preview 成功：multipart 绑定（file/bankId/format）透传 Service，200 信封承载行级明细与批次摘要。 */
    @Test
    void previewBindsMultipartAndReturnsEnvelope() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "questions.json",
                        MediaType.APPLICATION_JSON_VALUE,
                        "[]".getBytes(StandardCharsets.UTF_8));
        when(importBatchService.preview(file, 1L, "JSON", ADMIN)).thenReturn(previewResponse());

        mockMvc.perform(
                        multipart("/api/admin/uploads/preview")
                                .file(file)
                                .param("bankId", "1")
                                .param("format", "JSON")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.batchId").value("uuid-1"))
                .andExpect(jsonPath("$.data.bankId").value(1))
                .andExpect(jsonPath("$.data.status").value("PREVIEWED"))
                .andExpect(jsonPath("$.data.totalRows").value(3))
                .andExpect(jsonPath("$.data.validRows").value(2))
                .andExpect(jsonPath("$.data.invalidRows").value(1))
                .andExpect(jsonPath("$.data.rows[0].rowNo").value(3))
                .andExpect(jsonPath("$.data.rows[0].status").value("INVALID"))
                .andExpect(jsonPath("$.data.rows[0].errors[0].code").value("CHUNK_REF_MISMATCH"))
                .andExpect(jsonPath("$.data.errorSummary.code").value("ROW_VALIDATION_FAILED"));

        verify(importBatchService).preview(file, 1L, "JSON", ADMIN);
    }

    /** preview 缺少必填的 bankId 表单参数：40000 信封并在 data.errors 指明缺失参数，不触达 Service。 */
    @Test
    void previewMissingBankIdIs400() throws Exception {
        MockMultipartFile file =
                new MockMultipartFile(
                        "file",
                        "questions.json",
                        MediaType.APPLICATION_JSON_VALUE,
                        "[]".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(
                        multipart("/api/admin/uploads/preview")
                                .file(file)
                                .param("format", "JSON")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()))
                .andExpect(jsonPath("$.data.errors[0].field").value("bankId"));
        verifyNoInteractions(importBatchService);
    }

    /** commit 成功：200 信封回执 COMMITTED 状态与入库计数。 */
    @Test
    void commitReturnsCommittedEnvelope() throws Exception {
        when(importBatchService.commit("uuid-1", ADMIN)).thenReturn(committedBatch());

        mockMvc.perform(
                        post("/api/admin/uploads/{batchId}/commit", "uuid-1")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.batchId").value("uuid-1"))
                .andExpect(jsonPath("$.data.status").value("COMMITTED"))
                .andExpect(jsonPath("$.data.importedRows").value(47));

        verify(importBatchService).commit("uuid-1", ADMIN);
    }

    /** commit 错误映射：40405 批次不存在 → 404，40904 不可提交 → 409，业务码原样透传。 */
    @Test
    void commitMapsNotFoundAndConflict() throws Exception {
        when(importBatchService.commit("missing", ADMIN))
                .thenThrow(new BusinessException(ApiCode.IMPORT_BATCH_NOT_FOUND));
        mockMvc.perform(
                        post("/api/admin/uploads/{batchId}/commit", "missing")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiCode.IMPORT_BATCH_NOT_FOUND.code()));

        when(importBatchService.commit("dirty", ADMIN))
                .thenThrow(new BusinessException(ApiCode.IMPORT_NOT_COMMITTABLE));
        mockMvc.perform(
                        post("/api/admin/uploads/{batchId}/commit", "dirty")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ApiCode.IMPORT_NOT_COMMITTABLE.code()));
    }

    /** rollback 请求体校验：reason 为空串（@NotBlank）→ 40000 且 data.errors 带字段明细，不触达 Service。 */
    @Test
    void rollbackRejectsBlankReasonWith400() throws Exception {
        mockMvc.perform(
                        post("/api/admin/uploads/{batchId}/rollback", "uuid-1")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()))
                .andExpect(jsonPath("$.data.errors[0].field").value("reason"));
        verifyNoInteractions(importBatchService);
    }

    /** rollback 成功：reason 透传 Service，200 信封回执 ROLLED_BACK 状态。 */
    @Test
    void rollbackForwardsReasonAndReturnsEnvelope() throws Exception {
        when(importBatchService.rollback("uuid-1", "内容有误", ADMIN)).thenReturn(rolledBackBatch());

        mockMvc.perform(
                        post("/api/admin/uploads/{batchId}/rollback", "uuid-1")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"内容有误\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.status").value("ROLLED_BACK"));

        verify(importBatchService).rollback("uuid-1", "内容有误", ADMIN);
    }

    /** rollback 缺少请求体：按 40001 收敛为统一信封（body 必填），不触达 Service。 */
    @Test
    void rollbackWithoutBodyIsMalformedRequest() throws Exception {
        mockMvc.perform(
                        post("/api/admin/uploads/{batchId}/rollback", "uuid-1")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.MALFORMED_REQUEST.code()));
        verifyNoInteractions(importBatchService);
    }

    /** 批次列表：page/size/bankId/status/from/to 全量透传，200 信封为 PageResponse 形状。 */
    @Test
    void listForwardsFiltersAndReturnsPageEnvelope() throws Exception {
        when(importBatchService.list(2, 5, 1L, "COMMITTED", "2026-10-01", "2026-10-31"))
                .thenReturn(PageResponse.of(List.of(committedBatch(), rolledBackBatch()), 2, 5, 2));

        mockMvc.perform(
                        get("/api/admin/uploads")
                                .queryParam("page", "2")
                                .queryParam("size", "5")
                                .queryParam("bankId", "1")
                                .queryParam("status", "COMMITTED")
                                .queryParam("from", "2026-10-01")
                                .queryParam("to", "2026-10-31")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(5))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].status").value("COMMITTED"))
                .andExpect(jsonPath("$.data.items[1].status").value("ROLLED_BACK"));

        verify(importBatchService).list(2, 5, 1L, "COMMITTED", "2026-10-01", "2026-10-31");
    }

    /** 批次列表缺省分页：page=1、size=20，筛选参数缺省为 null 透传。 */
    @Test
    void listUsesDefaultPagingAndNullFilters() throws Exception {
        when(importBatchService.list(1, 20, null, null, null, null))
                .thenReturn(PageResponse.of(List.of(), 1, 20, 0));

        mockMvc.perform(
                        get("/api/admin/uploads")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20));

        verify(importBatchService).list(1, 20, null, null, null, null);
    }

    /** 批次详情：batchId 与行分页参数透传，200 信封为 batch + rows 嵌套形状。 */
    @Test
    void detailReturnsBatchWithRowsPage() throws Exception {
        when(importBatchService.detail("uuid-1", 2, 5, "INVALID"))
                .thenReturn(
                        new ImportBatchDetailResponse(
                                previewedBatch(), PageResponse.of(List.of(invalidRow()), 2, 5, 3)));

        mockMvc.perform(
                        get("/api/admin/uploads/{batchId}", "uuid-1")
                                .queryParam("page", "2")
                                .queryParam("size", "5")
                                .queryParam("status", "INVALID")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.batch.batchId").value("uuid-1"))
                .andExpect(jsonPath("$.data.batch.status").value("PREVIEWED"))
                .andExpect(jsonPath("$.data.rows.page").value(2))
                .andExpect(jsonPath("$.data.rows.total").value(3))
                .andExpect(jsonPath("$.data.rows.items[0].rowNo").value(3))
                .andExpect(jsonPath("$.data.rows.items[0].status").value("INVALID"));

        verify(importBatchService).detail("uuid-1", 2, 5, "INVALID");
    }

    /** 批次详情错误映射：40405 批次不存在 → 404 信封。 */
    @Test
    void detailMapsNotFoundTo404() throws Exception {
        when(importBatchService.detail("missing", 1, 20, null))
                .thenThrow(new BusinessException(ApiCode.IMPORT_BATCH_NOT_FOUND));

        mockMvc.perform(
                        get("/api/admin/uploads/{batchId}", "missing")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiCode.IMPORT_BATCH_NOT_FOUND.code()));

        verify(importBatchService).detail(eq("missing"), eq(1), eq(20), isNull());
    }

    /**
     * 构造含行级错误的预览响应。
     *
     * @return 预览响应
     */
    private static ImportPreviewResponse previewResponse() {
        return new ImportPreviewResponse(
                "uuid-1",
                1L,
                "PREVIEWED",
                "JSON",
                3,
                2,
                1,
                List.of(
                        new ImportRowView(
                                3,
                                "s_0003",
                                "INVALID",
                                List.of(
                                        new ImportRowError(
                                                "CHUNK_REF_MISMATCH",
                                                "chunks",
                                                "chunks[].answers[0] 按序拼接与 ref 不一致")),
                                null,
                                null)),
                new ImportErrorSummary(1, "ROW_VALIDATION_FAILED", "1 行未通过校验", List.of(3)));
    }

    /**
     * 构造 PREVIEWED 状态批次视图。
     *
     * @return 批次视图
     */
    private static ImportBatchViewResponse previewedBatch() {
        return new ImportBatchViewResponse(
                "uuid-1",
                1L,
                "PREVIEWED",
                "JSON",
                "questions.json",
                3,
                2,
                1,
                0,
                "2026-10-31T05:00:00Z",
                "2026-10-31T05:01:00Z",
                null,
                new ImportErrorSummary(1, "ROW_VALIDATION_FAILED", "1 行未通过校验", List.of(3)));
    }

    /**
     * 构造 COMMITTED 状态批次视图。
     *
     * @return 批次视图
     */
    private static ImportBatchViewResponse committedBatch() {
        return new ImportBatchViewResponse(
                "uuid-1",
                1L,
                "COMMITTED",
                "JSON",
                "questions.json",
                50,
                47,
                3,
                47,
                "2026-10-31T05:00:00Z",
                "2026-10-31T05:01:00Z",
                "2026-10-31T05:30:00Z",
                null);
    }

    /**
     * 构造 ROLLED_BACK 状态批次视图。
     *
     * @return 批次视图
     */
    private static ImportBatchViewResponse rolledBackBatch() {
        return new ImportBatchViewResponse(
                "uuid-1",
                1L,
                "ROLLED_BACK",
                "JSON",
                "questions.json",
                50,
                47,
                3,
                47,
                "2026-10-31T05:00:00Z",
                "2026-10-31T05:01:00Z",
                "2026-10-31T05:30:00Z",
                null);
    }

    /**
     * 构造一条校验失败的导入行。
     *
     * @return 行视图
     */
    private static ImportRowView invalidRow() {
        return new ImportRowView(
                3,
                "s_0003",
                "INVALID",
                List.of(new ImportRowError("CHUNK_REF_MISMATCH", "chunks", "与 ref 不一致")),
                null,
                null);
    }

    /** preview 缺少 file part：按 40000 收敛为统一信封（不落 500），不触达 Service。 */
    @Test
    void previewMissingFilePartIs400() throws Exception {
        mockMvc.perform(
                        multipart("/api/admin/uploads/preview")
                                .param("bankId", "1")
                                .param("format", "JSON")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));
        verifyNoInteractions(importBatchService);
    }
}

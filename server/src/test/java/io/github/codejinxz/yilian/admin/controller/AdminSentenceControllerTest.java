package io.github.codejinxz.yilian.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.api.PageResponse;
import io.github.codejinxz.yilian.common.logging.ErrorLogRecorder;
import io.github.codejinxz.yilian.common.logging.OperationLogContext;
import io.github.codejinxz.yilian.common.security.PasswordChangeRequiredInterceptor;
import io.github.codejinxz.yilian.common.security.RestAccessDeniedHandler;
import io.github.codejinxz.yilian.common.security.RestAuthenticationEntryPoint;
import io.github.codejinxz.yilian.common.security.SecurityConfig;
import io.github.codejinxz.yilian.common.security.YilianUserPrincipal;
import io.github.codejinxz.yilian.content.service.ContentAdminService;
import io.github.codejinxz.yilian.content.service.vo.SentenceDetail;

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
 * 题目域五端点（API §8.8）的 HTTP 层切片测试 [AC-6]：跨题库检索、批量状态/移动、单题状态流转与行内编辑的 绑定/校验/审计上下文，以及鉴权矩阵（匿名 401 / 非管理员
 * 403）[AC-1]； 批量跳过/回滚与版本字段语义由 {@code ContentAdminServiceImplTest} 覆盖，此处 Service 用 Mockito 替身。
 */
@WebMvcTest(controllers = AdminSentenceController.class)
@Import({
    SecurityConfig.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    PasswordChangeRequiredInterceptor.class
})
class AdminSentenceControllerTest {

    private static final YilianUserPrincipal ADMIN =
            YilianUserPrincipal.of(1L, "admin", "ADMIN", true);

    private static final YilianUserPrincipal USER =
            YilianUserPrincipal.of(7L, "learner", "USER", true);

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ContentAdminService contentAdminService;

    @MockitoBean private JwtDecoder jwtDecoder;

    @MockitoBean private ErrorLogRecorder errorLogRecorder;

    @AfterEach
    void cleanOperationLogContext() {
        // 切面不在 @WebMvcTest 上下文里，测试线程上的审计覆盖值需要手动清，避免串测。
        OperationLogContext.clear();
    }

    /** [AC-1] 匿名访问题目域端点：401 信封（code=40100），不触达 Service。 */
    @Test
    void sentenceEndpointsRejectAnonymousWith401_ac1() throws Exception {
        mockMvc.perform(get("/api/admin/sentences"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        mockMvc.perform(
                        post("/api/admin/sentences/bulk-status")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"ids\":[\"a\"],\"status\":\"DISABLED\",\"reason\":\"测试\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        mockMvc.perform(
                        patch("/api/admin/sentences/{sentenceId}/status", "01A")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"PUBLISHED\",\"reason\":\"恢复\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        mockMvc.perform(
                        patch("/api/admin/sentences/{sentenceId}", "01A")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"zh\":\"他越来越胖了呀。\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        verifyNoInteractions(contentAdminService);
    }

    /** [AC-1] 非 ADMIN 登录用户访问题目域：403 信封（code=40300），不触达 Service。 */
    @Test
    void sentenceEndpointsRejectNonAdminWith403_ac1() throws Exception {
        mockMvc.perform(
                        get("/api/admin/sentences")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));
        mockMvc.perform(
                        post("/api/admin/sentences/bulk-move")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"ids\":[\"a\"],\"bankId\":\"9\",\"reason\":\"归类\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));
        mockMvc.perform(
                        patch("/api/admin/sentences/{sentenceId}/status", "01A")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"PUBLISHED\",\"reason\":\"恢复\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));
        mockMvc.perform(
                        patch("/api/admin/sentences/{sentenceId}", "01A")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"zh\":\"他越来越胖了呀。\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));
        verifyNoInteractions(contentAdminService);
    }

    /** [AC-6] 跨题库检索：筛选参数全量透传，200 信封为 PageResponse + AdminSentence（含 bankId）形状。 */
    @Test
    void listForwardsFiltersAndReturnsAdminSentenceShape_ac6() throws Exception {
        when(contentAdminService.adminListSentences("肥胖", "5", "CET-4", "PUBLISHED", 2, 5))
                .thenReturn(PageResponse.of(List.of(sentenceDetail()), 2, 5, 1));

        mockMvc.perform(
                        get("/api/admin/sentences")
                                .queryParam("page", "2")
                                .queryParam("size", "5")
                                .queryParam("keyword", "肥胖")
                                .queryParam("bankId", "5")
                                .queryParam("level", "CET-4")
                                .queryParam("status", "PUBLISHED")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value("01J0000000000000000000000A"))
                .andExpect(jsonPath("$.data.items[0].bankId").value("5"))
                .andExpect(jsonPath("$.data.items[0].version").value(7))
                .andExpect(jsonPath("$.data.items[0].sentenceVersionId").value("11"))
                .andExpect(jsonPath("$.data.items[0].questionVersion").value(2))
                .andExpect(jsonPath("$.data.items[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.items[0].deleted").value(false))
                .andExpect(jsonPath("$.data.items[0].zh").value("他越来越胖。"))
                .andExpect(
                        jsonPath("$.data.items[0].ref").value("He is getting fatter and fatter."))
                .andExpect(jsonPath("$.data.items[0].level").value("CET-4"));

        verify(contentAdminService).adminListSentences("肥胖", "5", "CET-4", "PUBLISHED", 2, 5);
    }

    /** [AC-6] 跨题库检索缺省：page=1、size=20，筛选缺省 null 透传。 */
    @Test
    void listUsesDefaultPagingAndNullFilters_ac6() throws Exception {
        when(contentAdminService.adminListSentences(
                        isNull(), isNull(), isNull(), isNull(), eq(1), eq(20)))
                .thenReturn(PageResponse.of(List.of(), 1, 20, 0));

        mockMvc.perform(
                        get("/api/admin/sentences")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20));

        verify(contentAdminService)
                .adminListSentences(isNull(), isNull(), isNull(), isNull(), eq(1), eq(20));
    }

    /** [AC-6] 批量状态：ids/status/reason 透传 Service，{updated} 形状与审计上下文（摘要 targetId）。 */
    @Test
    void bulkStatusForwardsBodyAndWritesAuditContext_ac6() throws Exception {
        when(contentAdminService.adminBulkChangeStatus(
                        1L, List.of("01A", "01B"), "DISABLED", "批量停用"))
                .thenReturn(2);

        mockMvc.perform(
                        post("/api/admin/sentences/bulk-status")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"ids\":[\"01A\",\"01B\"],\"status\":\"DISABLED\",\"reason\":\"批量停用\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.updated").value(2));

        verify(contentAdminService)
                .adminBulkChangeStatus(1L, List.of("01A", "01B"), "DISABLED", "批量停用");
        assertThat(OperationLogContext.current())
                .hasValueSatisfying(
                        override -> {
                            assertThat(override.targetType()).isEqualTo("sentence");
                            assertThat(override.targetId()).isEqualTo("updated=2");
                            assertThat(override.reason()).isEqualTo("批量停用");
                        });
    }

    /** [AC-6] 批量移动：ids/bankId/reason 透传 Service，{moved} 形状与审计上下文。 */
    @Test
    void bulkMoveForwardsBodyAndWritesAuditContext_ac6() throws Exception {
        when(contentAdminService.adminBulkMove(1L, List.of("01A"), "9", "归类")).thenReturn(1);

        mockMvc.perform(
                        post("/api/admin/sentences/bulk-move")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"ids\":[\"01A\"],\"bankId\":\"9\",\"reason\":\"归类\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.moved").value(1));

        verify(contentAdminService).adminBulkMove(1L, List.of("01A"), "9", "归类");
        assertThat(OperationLogContext.current())
                .hasValueSatisfying(
                        override -> {
                            assertThat(override.targetId()).isEqualTo("moved=1");
                            assertThat(override.reason()).isEqualTo("归类");
                        });
    }

    /** [AC-6] 批量校验：空 ids / 空白 reason / 超 500 条 → 40000，不触达 Service。 */
    @Test
    void bulkStatusRejectsEmptyIdsBlankReasonAndOversizedBatch_ac6() throws Exception {
        mockMvc.perform(
                        post("/api/admin/sentences/bulk-status")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"ids\":[],\"status\":\"DISABLED\",\"reason\":\"测试\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));

        mockMvc.perform(
                        post("/api/admin/sentences/bulk-status")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"ids\":[\"01A\"],\"status\":\"DISABLED\",\"reason\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));

        mockMvc.perform(
                        post("/api/admin/sentences/bulk-status")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"ids\":"
                                                + quotedIds(501)
                                                + ",\"status\":\"DISABLED\",\"reason\":\"测试\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()))
                .andExpect(jsonPath("$.data.errors[0].field").value("ids"));

        verifyNoInteractions(contentAdminService);
    }

    /** [AC-6] 批量移动目标题库不存在：Service 抛 40401 → 404 信封透传。 */
    @Test
    void bulkMoveMapsMissingTargetBankTo40401_ac6() throws Exception {
        when(contentAdminService.adminBulkMove(1L, List.of("01A"), "9", "归类"))
                .thenThrow(new BusinessException(ApiCode.BANK_NOT_FOUND));

        mockMvc.perform(
                        post("/api/admin/sentences/bulk-move")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"ids\":[\"01A\"],\"bankId\":\"9\",\"reason\":\"归类\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiCode.BANK_NOT_FOUND.code()));
    }

    /** [AC-6] 单题状态流转：body 透传 Service，200 回更新后视图，审计上下文带题目 id 与原因。 */
    @Test
    void changeStatusForwardsBodyWritesAuditContextAndReturnsShape_ac6() throws Exception {
        when(contentAdminService.adminChangeSentenceStatus(1L, "01A", "PUBLISHED", "恢复"))
                .thenReturn(sentenceDetail());

        mockMvc.perform(
                        patch("/api/admin/sentences/{sentenceId}/status", "01A")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"PUBLISHED\",\"reason\":\"恢复\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.id").value("01J0000000000000000000000A"))
                .andExpect(jsonPath("$.data.bankId").value("5"))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.deleted").value(false))
                .andExpect(jsonPath("$.data.sentenceVersionId").value("11"))
                .andExpect(jsonPath("$.data.questionVersion").value(2));

        verify(contentAdminService).adminChangeSentenceStatus(1L, "01A", "PUBLISHED", "恢复");
        assertThat(OperationLogContext.current())
                .hasValueSatisfying(
                        override -> {
                            assertThat(override.targetType()).isEqualTo("sentence");
                            assertThat(override.targetId()).isEqualTo("01A");
                            assertThat(override.reason()).isEqualTo("恢复");
                        });
    }

    /** [AC-6] 单题状态流转校验：reason 空白 → 40000，不触达 Service。 */
    @Test
    void changeStatusRejectsBlankReasonWith400_ac6() throws Exception {
        mockMvc.perform(
                        patch("/api/admin/sentences/{sentenceId}/status", "01A")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"PUBLISHED\",\"reason\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));
        verifyNoInteractions(contentAdminService);
    }

    /** [AC-6] 单题状态流转题目缺失：Service 抛 40402 → 404 信封透传（题目不存在）。 */
    @Test
    void changeStatusMapsMissingSentenceTo40402_ac6() throws Exception {
        when(contentAdminService.adminChangeSentenceStatus(1L, "01GONE", "PUBLISHED", "恢复"))
                .thenThrow(new BusinessException(ApiCode.SENTENCE_NOT_FOUND));

        mockMvc.perform(
                        patch("/api/admin/sentences/{sentenceId}/status", "01GONE")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"PUBLISHED\",\"reason\":\"恢复\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiCode.SENTENCE_NOT_FOUND.code()))
                .andExpect(jsonPath("$.message").value("题目不存在"));
    }

    /** [AC-6] 行内编辑：字段全量透传 Service，200 回更新后视图（含 bankId），审计上下文带可选原因。 */
    @Test
    void updateForwardsBodyAndWritesAuditContext_ac6() throws Exception {
        when(contentAdminService.adminUpdateSentenceDetail(
                        1L,
                        "01A",
                        "他越来越胖了呀。",
                        "CET-6",
                        List.of("搭配", "新考点"),
                        "新解析。",
                        "7",
                        "PUBLISHED",
                        "编辑题目"))
                .thenReturn(sentenceDetail());

        mockMvc.perform(
                        patch("/api/admin/sentences/{sentenceId}", "01A")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"zh\":\"他越来越胖了呀。\",\"level\":\"CET-6\","
                                                + "\"tags\":[\"搭配\",\"新考点\"],\"note\":\"新解析。\","
                                                + "\"bankId\":\"7\",\"status\":\"PUBLISHED\",\"reason\":\"编辑题目\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.id").value("01J0000000000000000000000A"))
                .andExpect(jsonPath("$.data.zh").value("他越来越胖。"));

        verify(contentAdminService)
                .adminUpdateSentenceDetail(
                        1L,
                        "01A",
                        "他越来越胖了呀。",
                        "CET-6",
                        List.of("搭配", "新考点"),
                        "新解析。",
                        "7",
                        "PUBLISHED",
                        "编辑题目");
        assertThat(OperationLogContext.current())
                .hasValueSatisfying(
                        override -> {
                            assertThat(override.targetId()).isEqualTo("01A");
                            assertThat(override.reason()).isEqualTo("编辑题目");
                        });
    }

    /** [AC-6] 行内编辑校验：zh 少于 4 字符 → 40000 并在 data.errors 指明字段，不触达 Service。 */
    @Test
    void updateRejectsShortZhWith400_ac6() throws Exception {
        mockMvc.perform(
                        patch("/api/admin/sentences/{sentenceId}", "01A")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"zh\":\"中文\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()))
                .andExpect(jsonPath("$.data.errors[0].field").value("zh"));
        verifyNoInteractions(contentAdminService);
    }

    /** [AC-6] 行内编辑缺请求体：按 40001 收敛为统一信封，不触达 Service。 */
    @Test
    void updateWithoutBodyIsMalformedRequest_ac6() throws Exception {
        mockMvc.perform(
                        patch("/api/admin/sentences/{sentenceId}", "01A")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.MALFORMED_REQUEST.code()));
        verifyNoInteractions(contentAdminService);
    }

    /** [AC-6] 缺请求体：批量端点按 40001 收敛为统一信封，不触达 Service。 */
    @Test
    void bulkWithoutBodyIsMalformedRequest_ac6() throws Exception {
        mockMvc.perform(
                        post("/api/admin/sentences/bulk-move")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.MALFORMED_REQUEST.code()));
        verifyNoInteractions(contentAdminService);
    }

    /**
     * 构造 N 条去重题目 id 的 JSON 数组文本。
     *
     * @param count 条数
     * @return JSON 数组文本
     */
    private static String quotedIds(int count) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append('"').append(String.format("%04d", i)).append('"');
        }
        return builder.append(']').toString();
    }

    /** 构造题目视图。 */
    private static SentenceDetail sentenceDetail() {
        return new SentenceDetail(
                "01J0000000000000000000000A",
                "5",
                7L,
                "11",
                2,
                "PUBLISHED",
                false,
                "他越来越胖。",
                "He is getting fatter and fatter.",
                "主语 + 现在进行时。",
                List.of(),
                List.of(1),
                List.of("搭配"),
                "CET-4");
    }
}

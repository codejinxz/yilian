package io.github.codejinxz.yilian.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import io.github.codejinxz.yilian.content.service.vo.BankView;
import io.github.codejinxz.yilian.content.service.vo.SentenceChunk;
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
 * 题库域六端点（API §8.8）的 HTTP 层切片测试 [AC-5]：检索分页、新建、详情、部分更新、软删与题库内题目列表的 绑定/校验/审计上下文，以及鉴权矩阵（匿名 401 / 非管理员
 * 403）[AC-1]； 生命周期与批量语义分支由 {@code ContentAdminServiceImplTest} 覆盖，此处 Service 用 Mockito 替身。
 */
@WebMvcTest(controllers = AdminBankController.class)
@Import({
    SecurityConfig.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    PasswordChangeRequiredInterceptor.class
})
class AdminBankControllerTest {

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

    /** [AC-1] 匿名访问题库域端点：401 信封（code=40100），不触达 Service。 */
    @Test
    void bankEndpointsRejectAnonymousWith401_ac1() throws Exception {
        mockMvc.perform(get("/api/admin/banks"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        mockMvc.perform(delete("/api/admin/banks/{bankId}", 5).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        verifyNoInteractions(contentAdminService);
    }

    /** [AC-1] 非 ADMIN 登录用户访问题库域：403 信封（code=40300），不触达 Service。 */
    @Test
    void bankEndpointsRejectNonAdminWith403_ac1() throws Exception {
        mockMvc.perform(
                        get("/api/admin/banks")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));
        mockMvc.perform(
                        post("/api/admin/banks")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"题库\",\"visibility\":\"PUBLIC\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));
        verifyNoInteractions(contentAdminService);
    }

    /** [AC-5] 题库检索：筛选参数全量透传，200 信封为 PageResponse + BankView 形状。 */
    @Test
    void listForwardsFiltersAndReturnsPageEnvelope_ac5() throws Exception {
        when(contentAdminService.adminListBanks("官方", "PUBLIC", "DRAFT", 2, 5))
                .thenReturn(PageResponse.of(List.of(bankView()), 2, 5, 1));

        mockMvc.perform(
                        get("/api/admin/banks")
                                .queryParam("page", "2")
                                .queryParam("size", "5")
                                .queryParam("keyword", "官方")
                                .queryParam("visibility", "PUBLIC")
                                .queryParam("status", "DRAFT")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(5))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.items[0].id").value("5"))
                .andExpect(jsonPath("$.data.items[0].ownerId").isEmpty())
                .andExpect(jsonPath("$.data.items[0].name").value("官方题库"))
                .andExpect(jsonPath("$.data.items[0].description").value("说明"))
                .andExpect(jsonPath("$.data.items[0].visibility").value("PUBLIC"))
                .andExpect(jsonPath("$.data.items[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.data.items[0].version").value(3))
                .andExpect(jsonPath("$.data.items[0].sentenceCount").value(12));

        verify(contentAdminService).adminListBanks("官方", "PUBLIC", "DRAFT", 2, 5);
    }

    /** [AC-5] 新建题库：body 透传 Service，动态对象 id 与空原因写入审计上下文。 */
    @Test
    void createForwardsBodyAndWritesAuditContext_ac5() throws Exception {
        when(contentAdminService.adminCreateBank(1L, "新题库", null, "PUBLIC"))
                .thenReturn(BankView.of(12L, null, "新题库", null, "PUBLIC", "DRAFT", 0, 0));

        mockMvc.perform(
                        post("/api/admin/banks")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"name\":\"新题库\",\"description\":null,\"visibility\":\"PUBLIC\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.id").value("12"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        verify(contentAdminService).adminCreateBank(1L, "新题库", null, "PUBLIC");
        assertThat(OperationLogContext.current())
                .hasValueSatisfying(
                        override -> {
                            assertThat(override.targetType()).isEqualTo("bank");
                            assertThat(override.targetId()).isEqualTo("12");
                            assertThat(override.reason()).isNull();
                        });
    }

    /** [AC-5] 题库详情：Service 抛 40401 → 404 信封原样透传。 */
    @Test
    void getMapsMissingBankTo40401_ac5() throws Exception {
        when(contentAdminService.adminGetBank(5L))
                .thenThrow(new BusinessException(ApiCode.BANK_NOT_FOUND));

        mockMvc.perform(
                        get("/api/admin/banks/{bankId}", 5)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiCode.BANK_NOT_FOUND.code()))
                .andExpect(jsonPath("$.message").value("题库不存在"));
    }

    /** [AC-5] 部分更新：字段与可选 reason 透传 Service，审计上下文带对象 id 与原因。 */
    @Test
    void updateForwardsBodyAndWritesAuditContext_ac5() throws Exception {
        when(contentAdminService.adminUpdateBank(1L, 5L, null, null, null, "DISABLED"))
                .thenReturn(BankView.of(5L, null, "官方题库", "说明", "PUBLIC", "DISABLED", 4, 12));

        mockMvc.perform(
                        patch("/api/admin/banks/{bankId}", 5)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"DISABLED\",\"reason\":\"质量不达标\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        verify(contentAdminService).adminUpdateBank(1L, 5L, null, null, null, "DISABLED");
        assertThat(OperationLogContext.current())
                .hasValueSatisfying(
                        override -> {
                            assertThat(override.targetType()).isEqualTo("bank");
                            assertThat(override.targetId()).isEqualTo("5");
                            assertThat(override.reason()).isEqualTo("质量不达标");
                        });
    }

    /** [AC-5] 部分更新不带 reason（前端 updateAdminBank 形状）：审计原因回落为空。 */
    @Test
    void updateWithoutReasonKeepsAuditReasonEmpty_ac5() throws Exception {
        when(contentAdminService.adminUpdateBank(1L, 5L, "新名", null, null, null))
                .thenReturn(BankView.of(5L, null, "新名", "说明", "PUBLIC", "DRAFT", 4, 12));

        mockMvc.perform(
                        patch("/api/admin/banks/{bankId}", 5)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"新名\"}"))
                .andExpect(status().isOk());

        verify(contentAdminService).adminUpdateBank(1L, 5L, "新名", null, null, null);
        assertThat(OperationLogContext.current())
                .hasValueSatisfying(override -> assertThat(override.reason()).isNull());
    }

    /** [AC-5] 软删题库：reason 透传 Service，200 空数据信封，审计上下文带原因。 */
    @Test
    void deleteForwardsReasonAndReturnsEmptyEnvelope_ac5() throws Exception {
        mockMvc.perform(
                        delete("/api/admin/banks/{bankId}", 5)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"重复题库\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data").isEmpty());

        verify(contentAdminService).adminDeleteBank(1L, 5L, "重复题库");
        assertThat(OperationLogContext.current())
                .hasValueSatisfying(
                        override -> {
                            assertThat(override.targetId()).isEqualTo("5");
                            assertThat(override.reason()).isEqualTo("重复题库");
                        });
    }

    /** [AC-5] 软删校验：reason 空白 → 40000 且不触达 Service。 */
    @Test
    void deleteRejectsBlankReasonWith400_ac5() throws Exception {
        mockMvc.perform(
                        delete("/api/admin/banks/{bankId}", 5)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));
        verifyNoInteractions(contentAdminService);
    }

    /** [AC-5] 软删校验：reason 超 255 字符 → 40000 并在 data.errors 指明字段。 */
    @Test
    void deleteRejectsOverlongReasonWith400_ac5() throws Exception {
        mockMvc.perform(
                        delete("/api/admin/banks/{bankId}", 5)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"" + "长".repeat(256) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()))
                .andExpect(jsonPath("$.data.errors[0].field").value("reason"));
        verifyNoInteractions(contentAdminService);
    }

    /** [AC-5] 缺请求体：DELETE 按 40001 收敛为统一信封，不触达 Service。 */
    @Test
    void deleteWithoutBodyIsMalformedRequest_ac5() throws Exception {
        mockMvc.perform(
                        delete("/api/admin/banks/{bankId}", 5)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.MALFORMED_REQUEST.code()));
        verifyNoInteractions(contentAdminService);
    }

    /** [AC-5] 题库内题目列表：筛选透传，200 信封为 PageResponse + SentenceDetail 形状。 */
    @Test
    void listSentencesForwardsFiltersAndReturnsSentenceShape_ac5() throws Exception {
        when(contentAdminService.adminListBankSentences(5L, "他", "DRAFT", 2, 5))
                .thenReturn(PageResponse.of(List.of(sentenceDetail()), 2, 5, 1));

        mockMvc.perform(
                        get("/api/admin/banks/{bankId}/sentences", 5)
                                .queryParam("page", "2")
                                .queryParam("size", "5")
                                .queryParam("keyword", "他")
                                .queryParam("status", "DRAFT")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value("01J0000000000000000000000A"))
                .andExpect(jsonPath("$.data.items[0].bankId").value("5"))
                .andExpect(jsonPath("$.data.items[0].version").value(7))
                .andExpect(jsonPath("$.data.items[0].sentenceVersionId").value("11"))
                .andExpect(jsonPath("$.data.items[0].questionVersion").value(2))
                .andExpect(jsonPath("$.data.items[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.data.items[0].deleted").value(false))
                .andExpect(jsonPath("$.data.items[0].zh").value("他越来越胖。"))
                .andExpect(jsonPath("$.data.items[0].chunks[0].answers[0]").value("He"))
                .andExpect(jsonPath("$.data.items[0].blank[0]").value(1))
                .andExpect(jsonPath("$.data.items[0].tags[0]").value("搭配"))
                .andExpect(jsonPath("$.data.items[0].level").value("CET-4"));

        verify(contentAdminService).adminListBankSentences(5L, "他", "DRAFT", 2, 5);
    }

    /** 构造题库视图。 */
    private static BankView bankView() {
        return BankView.of(5L, null, "官方题库", "说明", "PUBLIC", "DRAFT", 3, 12);
    }

    /** 构造题目视图。 */
    private static SentenceDetail sentenceDetail() {
        return new SentenceDetail(
                "01J0000000000000000000000A",
                "5",
                7L,
                "11",
                2,
                "DRAFT",
                false,
                "他越来越胖。",
                "He is getting fatter and fatter.",
                "主语 + 现在进行时。",
                List.of(new SentenceChunk(0, "他", List.of("He"), "代词", null)),
                List.of(1),
                List.of("搭配"),
                "CET-4");
    }
}

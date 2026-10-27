package io.github.codejinxz.yilian.content.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
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
import io.github.codejinxz.yilian.content.service.ContentQueryService;
import io.github.codejinxz.yilian.content.service.dto.BankView;
import io.github.codejinxz.yilian.content.service.dto.SentenceChunk;
import io.github.codejinxz.yilian.content.service.dto.SentenceDetail;
import io.github.codejinxz.yilian.content.service.dto.SentencePage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

/**
 * 题库只读接口的 MVC 切片测试：加载真实 {@link SecurityConfig} 验证匿名放行与鉴权边界， Service 用 Mockito 替身。
 *
 * <p>刻意不使用 @SpringBootTest：存量 ImportBatch 实体映射使全上下文无法启动（ddl-auto=validate）， 且本切片只需覆盖「路由 + 安全规则 +
 * 信封结构」，可见性判定逻辑由 {@code ContentQueryServiceImplTest} 的纯单元测试负责。
 */
@WebMvcTest(controllers = BankController.class)
@Import({
    SecurityConfig.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    PasswordChangeRequiredInterceptor.class
})
class BankControllerTest {

    private static final YilianUserPrincipal USER =
            YilianUserPrincipal.of(7L, "learner", "USER", true);

    @Autowired private MockMvc mockMvc;

    @MockitoBean private ContentQueryService contentQueryService;

    @MockitoBean private JwtDecoder jwtDecoder;

    @MockitoBean private ErrorLogRecorder errorLogRecorder;

    /** 匿名列表返回 200 与分页信封，且 Service 收到的用户 id 为 null（只看公开题库）。 */
    @Test
    void listBanksAnonymousReturnsPublicPage() throws Exception {
        when(contentQueryService.listBanks(isNull(), isNull(), isNull(), isNull(), eq(1), eq(20)))
                .thenReturn(
                        PageResponse.of(
                                List.of(
                                        BankView.of(
                                                1L,
                                                null,
                                                "官方题库",
                                                "专八翻译语块",
                                                "PUBLIC",
                                                "PUBLISHED",
                                                12,
                                                48L)),
                                1,
                                20,
                                1));

        mockMvc.perform(get("/api/banks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.items[0].id").value("1"))
                .andExpect(jsonPath("$.data.items[0].ownerId").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].sentenceCount").value(48))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));

        verify(contentQueryService)
                .listBanks(isNull(), isNull(), isNull(), isNull(), eq(1), eq(20));
    }

    /** 登录用户列表把用户 id 透传给 Service，私有题库过滤在 Service 内完成。 */
    @Test
    void listBanksLoggedInForwardsUserId() throws Exception {
        when(contentQueryService.listBanks(eq(7L), isNull(), isNull(), isNull(), eq(1), eq(20)))
                .thenReturn(PageResponse.of(List.of(), 1, 20, 0));

        mockMvc.perform(
                        get("/api/banks")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());

        verify(contentQueryService).listBanks(eq(7L), isNull(), isNull(), isNull(), eq(1), eq(20));
    }

    /** 匿名访问不存在或私有题库，统一 40401 信封，无法区分二者。 */
    @Test
    void getBankAnonymousPrivateIs404() throws Exception {
        when(contentQueryService.getBank(99L, null, false))
                .thenThrow(new BusinessException(ApiCode.BANK_NOT_FOUND));

        mockMvc.perform(get("/api/banks/{bankId}", 99))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiCode.BANK_NOT_FOUND.code()));

        verify(contentQueryService).getBank(99L, null, false);
    }

    /** 登录所有者可取回自己的私有题库详情。 */
    @Test
    void getBankLoggedInOwnerReturnsView() throws Exception {
        when(contentQueryService.getBank(99L, 7L, false))
                .thenReturn(BankView.of(99L, 7L, "我的题库", null, "PRIVATE", "PUBLISHED", 3, 5));

        mockMvc.perform(
                        get("/api/banks/{bankId}", 99)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("99"))
                .andExpect(jsonPath("$.data.ownerId").value("7"))
                .andExpect(jsonPath("$.data.visibility").value("PRIVATE"));
    }

    /** 增量拉取响应信封：tombstone 不带内容字段，已发布题目带完整语块。 */
    @Test
    void listSentencesReturnsVersionedEnvelope() throws Exception {
        when(contentQueryService.listSentences(1L, 0, 100, null, false))
                .thenReturn(
                        new SentencePage(
                                12,
                                List.of(
                                        SentenceDetail.tombstone(
                                                "01JTOMBST00000000000000000A", 1, 13, "RECYCLED"),
                                        publishedDetail()),
                                12,
                                false));

        mockMvc.perform(get("/api/banks/{bankId}/sentences", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.version").value(12))
                .andExpect(jsonPath("$.data.items[0].id").value("01JTOMBST00000000000000000A"))
                .andExpect(jsonPath("$.data.items[0].deleted").value(true))
                .andExpect(jsonPath("$.data.items[0].zh").value(nullValue()))
                .andExpect(jsonPath("$.data.items[1].zh").value("他越来越重视这件事。"))
                .andExpect(jsonPath("$.data.items[1].sentenceVersionId").value("1001"))
                .andExpect(jsonPath("$.data.items[1].questionVersion").value(3))
                .andExpect(jsonPath("$.data.items[1].chunks[0].answers[0]").value("He"))
                .andExpect(jsonPath("$.data.items[1].blank[0]").value(1))
                .andExpect(jsonPath("$.data.items[1].tags[0]").value("副词结构"))
                .andExpect(jsonPath("$.data.nextSinceVersion").value(12))
                .andExpect(jsonPath("$.data.hasMore").value(false));

        verify(contentQueryService).listSentences(1L, 0, 100, null, false);
    }

    /** sinceVersion/limit 查询参数按默认值与显式值透传。 */
    @Test
    void listSentencesForwardsCursorParams() throws Exception {
        when(contentQueryService.listSentences(1L, 5, 50, null, false))
                .thenReturn(new SentencePage(12, List.of(), 5, false));

        mockMvc.perform(
                        get("/api/banks/{bankId}/sentences", 1)
                                .queryParam("sinceVersion", "5")
                                .queryParam("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.nextSinceVersion").value(5));

        verify(contentQueryService).listSentences(1L, 5, 50, null, false);
    }

    /** sinceVersion 越过题库版本返回 40005 信封。 */
    @Test
    void listSentencesInvalidSinceVersionIs400() throws Exception {
        when(contentQueryService.listSentences(1L, 999, 100, null, false))
                .thenThrow(new BusinessException(ApiCode.INVALID_SINCE_VERSION));

        mockMvc.perform(get("/api/banks/{bankId}/sentences", 1).queryParam("sinceVersion", "999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.INVALID_SINCE_VERSION.code()));
    }

    /** 分页参数越界返回 40004 信封。 */
    @Test
    void listBanksInvalidPaginationIs400() throws Exception {
        when(contentQueryService.listBanks(isNull(), isNull(), isNull(), isNull(), eq(1), eq(1000)))
                .thenThrow(new BusinessException(ApiCode.INVALID_PAGINATION));

        mockMvc.perform(get("/api/banks").queryParam("size", "1000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.INVALID_PAGINATION.code()));
    }

    /** 路径变量类型不匹配（bankId 非数字）收敛为 400 信封，不落 500。 */
    @Test
    void getBankMalformedIdIs400() throws Exception {
        mockMvc.perform(get("/api/banks/{bankId}", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));
    }

    /** 非 GET 方法对题库路径仍要求登录：匿名带 CSRF 的 POST 返回 401 信封。 */
    @Test
    void nonGetStillRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/banks").with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
    }

    /** 其余规则不变：匿名访问 /api/admin/** 仍被拒。 */
    @Test
    void adminRoutesStayLocked() throws Exception {
        mockMvc.perform(get("/api/admin/banks"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
    }

    /**
     * 构造一条已发布题目视图。
     *
     * @return 完整内容视图
     */
    private static SentenceDetail publishedDetail() {
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
}

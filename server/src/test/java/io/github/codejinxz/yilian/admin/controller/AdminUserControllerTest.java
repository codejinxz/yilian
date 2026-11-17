package io.github.codejinxz.yilian.admin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
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
import io.github.codejinxz.yilian.system.service.UserAccountService;
import io.github.codejinxz.yilian.system.service.vo.AdminPasswordResetView;
import io.github.codejinxz.yilian.system.service.vo.AdminUserView;

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

/**
 * 用户域三端点（API §8.1）的 HTTP 层切片测试 [AC-3]：检索分页、启停与改角色的绑定/校验/审计上下文， 以及鉴权矩阵（匿名 401 / 非管理员
 * 403）[AC-1]；「自己停自己」「自己降自己」等业务分支由 {@code UserAccountServiceImplTest} 覆盖，此处 Service 用 Mockito 替身。
 */
@WebMvcTest(controllers = AdminUserController.class)
@Import({
    SecurityConfig.class,
    RestAuthenticationEntryPoint.class,
    RestAccessDeniedHandler.class,
    PasswordChangeRequiredInterceptor.class
})
class AdminUserControllerTest {

    private static final YilianUserPrincipal ADMIN =
            YilianUserPrincipal.of(1L, "admin", "ADMIN", true);

    private static final YilianUserPrincipal USER =
            YilianUserPrincipal.of(7L, "learner", "USER", true);

    @Autowired private MockMvc mockMvc;

    @MockitoBean private UserAccountService userAccountService;

    @MockitoBean private JwtDecoder jwtDecoder;

    @MockitoBean private ErrorLogRecorder errorLogRecorder;

    @AfterEach
    void cleanOperationLogContext() {
        // 切面不在 @WebMvcTest 上下文里，测试线程上的审计覆盖值需要手动清，避免串测。
        OperationLogContext.clear();
    }

    /** [AC-1] 匿名访问用户域端点：401 信封（code=40100），不触达 Service。 */
    @Test
    void userEndpointsRejectAnonymousWith401_ac1() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        mockMvc.perform(patch("/api/admin/users/{userId}/status", 3).with(csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ApiCode.AUTH_REQUIRED.code()));
        verifyNoInteractions(userAccountService);
    }

    /** [AC-1] 非 ADMIN 登录用户访问用户域：403 信封（code=40300），不触达 Service。 */
    @Test
    void userEndpointsRejectNonAdminWith403_ac1() throws Exception {
        mockMvc.perform(
                        get("/api/admin/users")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));
        mockMvc.perform(
                        patch("/api/admin/users/{userId}/status", 3)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));
        verifyNoInteractions(userAccountService);
    }

    /** [AC-3] 用户检索：筛选参数全量透传，200 信封为 PageResponse 形状（items/page/size/total/totalPages/hasNext）。 */
    @Test
    void listForwardsFiltersAndReturnsPageEnvelope_ac3() throws Exception {
        when(userAccountService.adminSearch("learn", "ADMIN", "active", 2, 5))
                .thenReturn(PageResponse.of(List.of(userView()), 2, 5, 1));

        mockMvc.perform(
                        get("/api/admin/users")
                                .queryParam("page", "2")
                                .queryParam("size", "5")
                                .queryParam("keyword", "learn")
                                .queryParam("role", "ADMIN")
                                .queryParam("status", "active")
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
                .andExpect(jsonPath("$.data.items[0].id").value("3"))
                .andExpect(jsonPath("$.data.items[0].username").value("learner"))
                .andExpect(jsonPath("$.data.items[0].role").value("USER"))
                .andExpect(jsonPath("$.data.items[0].enabled").value(true))
                .andExpect(jsonPath("$.data.items[0].totpEnabled").value(false))
                .andExpect(jsonPath("$.data.items[0].deleted").value(false))
                .andExpect(jsonPath("$.data.items[0].createdAt").value("2026-01-15T08:00:00Z"))
                .andExpect(jsonPath("$.data.items[0].lastLoginAt").value("2026-10-30T12:30:00Z"))
                .andExpect(jsonPath("$.data.items[0].disabledReason").isEmpty());

        verify(userAccountService).adminSearch("learn", "ADMIN", "active", 2, 5);
    }

    /** [AC-3] 用户检索缺省：page=1、size=20，筛选参数缺省为 null 透传。 */
    @Test
    void listUsesDefaultPagingAndNullFilters_ac3() throws Exception {
        when(userAccountService.adminSearch(isNull(), isNull(), isNull(), eq(1), eq(20)))
                .thenReturn(PageResponse.of(List.of(), 1, 20, 0));

        mockMvc.perform(
                        get("/api/admin/users")
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20));

        verify(userAccountService).adminSearch(isNull(), isNull(), isNull(), eq(1), eq(20));
    }

    /** [AC-3] 停用账号：enabled/reason 透传 Service，动态对象 id 与原因写入审计上下文。 */
    @Test
    void setStatusForwardsBodyAndWritesAuditContext_ac3() throws Exception {
        when(userAccountService.adminSetStatus(1L, 3L, false, "违规发内容")).thenReturn(disabledView());

        mockMvc.perform(
                        patch("/api/admin/users/{userId}/status", 3)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"enabled\":false,\"reason\":\"违规发内容\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.id").value("3"))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.disabledReason").value("违规发内容"));

        verify(userAccountService).adminSetStatus(1L, 3L, false, "违规发内容");
        assertThat(OperationLogContext.current())
                .hasValueSatisfying(
                        override -> {
                            assertThat(override.targetType()).isEqualTo("user");
                            assertThat(override.targetId()).isEqualTo("3");
                            assertThat(override.reason()).isEqualTo("违规发内容");
                        });
    }

    /** [AC-6a] 重置密码端点：reason 必填透传，200 信封回一次性临时口令回执，审计上下文写入目标与原因。 */
    @Test
    void resetPasswordForwardsReasonAndReturnsOneTimeView_ac6a() throws Exception {
        when(userAccountService.adminResetPassword(1L, 3L, "用户忘记密码"))
                .thenReturn(new AdminPasswordResetView("AbC23456MNPQ", "3", "learner"));

        mockMvc.perform(
                        post("/api/admin/users/{userId}/reset-password", 3)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"用户忘记密码\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.temporaryPassword").value("AbC23456MNPQ"))
                .andExpect(jsonPath("$.data.userId").value("3"))
                .andExpect(jsonPath("$.data.username").value("learner"));

        verify(userAccountService).adminResetPassword(1L, 3L, "用户忘记密码");
        assertThat(OperationLogContext.current())
                .hasValueSatisfying(
                        override -> {
                            assertThat(override.targetType()).isEqualTo("user");
                            assertThat(override.targetId()).isEqualTo("3");
                            assertThat(override.reason()).isEqualTo("用户忘记密码");
                        });
    }

    /** [AC-6a] 重置密码：缺失原因 40000（Bean Validation），不触达 Service。 */
    @Test
    void resetPasswordRejectsBlankReason_ac6a() throws Exception {
        mockMvc.perform(
                        post("/api/admin/users/{userId}/reset-password", 3)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));

        verifyNoInteractions(userAccountService);
    }

    /** [AC-6a] 自己重置自己：Service 抛 40300 原样透传。 */
    @Test
    void resetPasswordMapsSelfResetDenied_ac6a() throws Exception {
        when(userAccountService.adminResetPassword(1L, 1L, "测试"))
                .thenThrow(new BusinessException(ApiCode.ACCESS_DENIED, "不能重置自己的密码，请走自助改密"));

        mockMvc.perform(
                        post("/api/admin/users/{userId}/reset-password", 1)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"测试\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()))
                .andExpect(jsonPath("$.message").value("不能重置自己的密码，请走自助改密"));
    }

    /** [AC-6c] 重置两步验证端点：reason 必填透传，200 固定提示，审计上下文写入目标与原因。 */
    @Test
    void reset2faForwardsReasonAndWritesAuditContext_ac6c() throws Exception {
        mockMvc.perform(
                        post("/api/admin/users/{userId}/reset-2fa", 3)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"用户恢复码全失\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.message").value("已重置该用户的两步验证"));

        verify(userAccountService).adminReset2fa(1L, 3L, "用户恢复码全失");
        assertThat(OperationLogContext.current())
                .hasValueSatisfying(
                        override -> {
                            assertThat(override.targetType()).isEqualTo("user");
                            assertThat(override.targetId()).isEqualTo("3");
                            assertThat(override.reason()).isEqualTo("用户恢复码全失");
                        });
    }

    /** [AC-6c] 重置两步验证：缺失原因 40000（Bean Validation），不触达 Service。 */
    @Test
    void reset2faRejectsBlankReason_ac6c() throws Exception {
        mockMvc.perform(
                        post("/api/admin/users/{userId}/reset-2fa", 3)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));

        verifyNoInteractions(userAccountService);
    }

    /** [AC-6c] 重置两步验证：非管理员 403；目标不存在由 Service 抛 40400 原样透传。 */
    @Test
    void reset2faRejectsNonAdminAndMapsNotFound_ac6c() throws Exception {
        mockMvc.perform(
                        post("/api/admin/users/{userId}/reset-2fa", 3)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));
        doThrow(new BusinessException(ApiCode.NOT_FOUND, "用户不存在"))
                .when(userAccountService)
                .adminReset2fa(1L, 99L, "x");
        mockMvc.perform(
                        post("/api/admin/users/{userId}/reset-2fa", 99)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ApiCode.NOT_FOUND.code()));
        verify(userAccountService).adminReset2fa(1L, 99L, "x");
    }

    /** [AC-6b] 删除账号端点：DELETE + reason 透传，200 回软删后的视图，审计上下文写入。 */
    @Test
    void deleteUserForwardsReasonAndReturnsDeletedView_ac6b() throws Exception {
        AdminUserView deleted =
                new AdminUserView(
                        "3",
                        "learner",
                        null,
                        "USER",
                        false,
                        false,
                        true,
                        Instant.EPOCH,
                        null,
                        null);
        when(userAccountService.adminDeleteUser(1L, 3L, "用户申请注销")).thenReturn(deleted);

        mockMvc.perform(
                        delete("/api/admin/users/{userId}", 3)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"用户申请注销\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.deleted").value(true))
                .andExpect(jsonPath("$.data.enabled").value(false));

        verify(userAccountService).adminDeleteUser(1L, 3L, "用户申请注销");
        assertThat(OperationLogContext.current())
                .hasValueSatisfying(
                        override -> {
                            assertThat(override.targetType()).isEqualTo("user");
                            assertThat(override.targetId()).isEqualTo("3");
                            assertThat(override.reason()).isEqualTo("用户申请注销");
                        });
    }

    /** [AC-6b] 删除账号：缺失原因 40000；已删目标由 Service 抛 40400（软删惯例，见实现注释）。 */
    @Test
    void deleteUserRejectsBlankReason_ac6b() throws Exception {
        mockMvc.perform(
                        delete("/api/admin/users/{userId}", 3)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));

        verifyNoInteractions(userAccountService);
    }

    /** [AC-6b] 匿名/非管理员访问两个新端点沿用鉴权矩阵（这里补非 ADMIN 的 403）。 */
    @Test
    void newUserEndpointsRejectNonAdminWith403_ac6b() throws Exception {
        mockMvc.perform(
                        post("/api/admin/users/{userId}/reset-password", 3)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));
        mockMvc.perform(
                        delete("/api/admin/users/{userId}", 3)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        USER, null, "ROLE_USER")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ApiCode.ACCESS_DENIED.code()));
        verifyNoInteractions(userAccountService);
    }

    /** [AC-3] 自己停自己：Service 抛业务失败 → 4xx 信封原样透传（40900「不能停用自己」）。 */
    @Test
    void setStatusMapsSelfDisableBusinessFailure_ac3() throws Exception {
        when(userAccountService.adminSetStatus(1L, 1L, false, "测试"))
                .thenThrow(new BusinessException(ApiCode.IDEMPOTENT_CONFLICT, "不能停用自己"));

        mockMvc.perform(
                        patch("/api/admin/users/{userId}/status", 1)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"enabled\":false,\"reason\":\"测试\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ApiCode.IDEMPOTENT_CONFLICT.code()))
                .andExpect(jsonPath("$.message").value("不能停用自己"));
    }

    /** [AC-3] 变更角色：role/reason 透传 Service，动态对象 id 与原因写入审计上下文。 */
    @Test
    void setRoleForwardsBodyAndWritesAuditContext_ac3() throws Exception {
        when(userAccountService.adminSetRole(1L, 3L, "ADMIN", "接手运营")).thenReturn(promotedView());

        mockMvc.perform(
                        patch("/api/admin/users/{userId}/role", 3)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"role\":\"ADMIN\",\"reason\":\"接手运营\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ApiCode.SUCCESS.code()))
                .andExpect(jsonPath("$.data.role").value("ADMIN"));

        verify(userAccountService).adminSetRole(1L, 3L, "ADMIN", "接手运营");
        assertThat(OperationLogContext.current())
                .hasValueSatisfying(
                        override -> {
                            assertThat(override.targetId()).isEqualTo("3");
                            assertThat(override.reason()).isEqualTo("接手运营");
                        });
    }

    /** [AC-3] 参数校验：reason 超过 255 字符 → 40000 信封并在 data.errors 指明字段，不触达 Service。 */
    @Test
    void setStatusRejectsOverlongReasonWith400_ac3() throws Exception {
        mockMvc.perform(
                        patch("/api/admin/users/{userId}/status", 3)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"enabled\":false,\"reason\":\""
                                                + "长".repeat(256)
                                                + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()))
                .andExpect(jsonPath("$.data.errors[0].field").value("reason"));
        verifyNoInteractions(userAccountService);
    }

    /** [AC-3] 参数校验：reason 空白 / role 越界 → 40000 信封，不触达 Service。 */
    @Test
    void patchBodiesRejectBlankReasonAndBadRole_ac3() throws Exception {
        mockMvc.perform(
                        patch("/api/admin/users/{userId}/status", 3)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"enabled\":false,\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));

        mockMvc.perform(
                        patch("/api/admin/users/{userId}/role", 3)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"role\":\"OWNER\",\"reason\":\"试试\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.VALIDATION_FAILED.code()));

        verifyNoInteractions(userAccountService);
    }

    /** [AC-3] 缺请求体：按 40001 收敛为统一信封，不触达 Service。 */
    @Test
    void patchWithoutBodyIsMalformedRequest_ac3() throws Exception {
        mockMvc.perform(
                        patch("/api/admin/users/{userId}/role", 3)
                                .with(
                                        authentication(
                                                new TestingAuthenticationToken(
                                                        ADMIN, null, "ROLE_ADMIN")))
                                .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ApiCode.MALFORMED_REQUEST.code()));
        verifyNoInteractions(userAccountService);
    }

    /**
     * 构造启用态用户视图。
     *
     * @return 用户视图
     */
    private static AdminUserView userView() {
        return new AdminUserView(
                "3",
                "learner",
                "learner@example.com",
                "USER",
                true,
                false,
                false,
                Instant.parse("2026-01-15T08:00:00Z"),
                Instant.parse("2026-10-30T12:30:00Z"),
                null);
    }

    /**
     * 构造停用态用户视图。
     *
     * @return 用户视图
     */
    private static AdminUserView disabledView() {
        return new AdminUserView(
                "3",
                "learner",
                "learner@example.com",
                "USER",
                false,
                false,
                false,
                Instant.parse("2026-01-15T08:00:00Z"),
                Instant.parse("2026-10-30T12:30:00Z"),
                "违规发内容");
    }

    /**
     * 构造已升为管理员的角色视图。
     *
     * @return 用户视图
     */
    private static AdminUserView promotedView() {
        return new AdminUserView(
                "3",
                "learner",
                "learner@example.com",
                "ADMIN",
                true,
                false,
                false,
                Instant.parse("2026-01-15T08:00:00Z"),
                Instant.parse("2026-10-30T12:30:00Z"),
                null);
    }
}

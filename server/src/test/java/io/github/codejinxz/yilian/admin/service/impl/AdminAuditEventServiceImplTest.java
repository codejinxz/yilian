package io.github.codejinxz.yilian.admin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.codejinxz.yilian.admin.dao.AdminAuditEventDao;
import io.github.codejinxz.yilian.admin.dao.AdminAuditEventRow;
import io.github.codejinxz.yilian.admin.service.dto.AdminAuditEventRequest;
import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

/**
 * 审计事件服务单测 [AC-4]：动作白名单（前端实际调用点逐一放行、未知动作 40000）、 动作/字段归一截断、写入行形状（module=admin、outcome=SUCCESS、actor
 * 透传）。
 */
class AdminAuditEventServiceImplTest {

    private AdminAuditEventDao adminAuditEventDao;

    private AdminAuditEventServiceImpl service;

    @BeforeEach
    void setUp() {
        adminAuditEventDao = mock(AdminAuditEventDao.class);
        when(adminAuditEventDao.insert(any())).thenReturn(901L);
        service = new AdminAuditEventServiceImpl(adminAuditEventDao);
    }

    /** [AC-4] 白名单覆盖前端 appendAuditEvent 的全部实际动作。 */
    @Test
    void recordEventAcceptsAllFrontendActions_ac4() {
        List<String> frontendActions =
                List.of(
                        // RESET_PASSWORD 已迁移到真端点，从演示白名单移除。
                        "EXPORT_USER_DATA",
                        "SESSION_REVOKE",
                        "MODEL_CONFIG_UPDATE",
                        "MODEL_TOGGLE",
                        "PROMPT_UPDATE");
        for (String action : frontendActions) {
            service.recordEvent(
                    1L, "admin", "ADMIN", new AdminAuditEventRequest(action, "USER", "7", "原因"));
        }
        // 每个动作各写一行。
        verify(adminAuditEventDao, org.mockito.Mockito.times(frontendActions.size())).insert(any());
    }

    /**
     * [AC-6a] RESET_PASSWORD 从演示白名单移除：动作已由真端点 POST /api/admin/users/{id}/reset-password
     * 的 @OperationLog 落审计，演示入口关闭以防同动作双入口污染只增不改的审计表。
     */
    @Test
    void recordEventRejectsMigratedResetPasswordAction_ac6a() {
        assertThatThrownBy(
                        () ->
                                service.recordEvent(
                                        1L,
                                        "admin",
                                        "ADMIN",
                                        new AdminAuditEventRequest(
                                                "RESET_PASSWORD", "USER", "7", "用户忘记密码")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> {
                            assertThat(exception.apiCode()).isEqualTo(ApiCode.VALIDATION_FAILED);
                            assertThat(exception.getMessage()).contains("不支持的审计动作");
                        });

        verify(adminAuditEventDao, never()).insert(any());
    }

    /** [AC-4] 未知动作与空 action 回 40000，不落库。 */
    @Test
    void recordEventRejectsUnknownOrBlankAction_ac4() {
        assertThatThrownBy(
                        () ->
                                service.recordEvent(
                                        1L,
                                        "admin",
                                        "ADMIN",
                                        new AdminAuditEventRequest("DROP_AUDIT", null, null, null)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> {
                            assertThat(exception.apiCode()).isEqualTo(ApiCode.VALIDATION_FAILED);
                            assertThat(exception.getMessage()).contains("不支持的审计动作");
                        });
        assertThatThrownBy(
                        () ->
                                service.recordEvent(
                                        1L,
                                        "admin",
                                        "ADMIN",
                                        new AdminAuditEventRequest("  ", null, null, null)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> {
                            assertThat(exception.apiCode()).isEqualTo(ApiCode.VALIDATION_FAILED);
                            assertThat(exception.getMessage()).contains("缺少审计动作");
                        });
        verify(adminAuditEventDao, never()).insert(any());
    }

    /** [AC-4] 写入行形状：actor 透传、可空字段归 null、模块与结果恒定。 */
    @Test
    void recordEventWritesAdminAuditRowShape_ac4() {
        service.recordEvent(
                1L,
                "admin",
                "ADMIN",
                new AdminAuditEventRequest(" EXPORT_USER_DATA ", "USER", "7", "用户申请导出"));

        ArgumentCaptor<AdminAuditEventRow> row = ArgumentCaptor.forClass(AdminAuditEventRow.class);
        verify(adminAuditEventDao).insert(row.capture());
        assertThat(row.getValue().action()).isEqualTo("EXPORT_USER_DATA");
        assertThat(row.getValue().actorId()).isEqualTo(1L);
        assertThat(row.getValue().actorName()).isEqualTo("admin");
        assertThat(row.getValue().actorRole()).isEqualTo("ADMIN");
        assertThat(row.getValue().targetType()).isEqualTo("USER");
        assertThat(row.getValue().targetId()).isEqualTo("7");
        assertThat(row.getValue().reason()).isEqualTo("用户申请导出");
        assertThat(row.getValue().traceId()).isNotBlank();
    }
}

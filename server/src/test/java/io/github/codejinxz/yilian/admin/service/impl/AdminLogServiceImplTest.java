package io.github.codejinxz.yilian.admin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.codejinxz.yilian.admin.dao.AdminAuditLogQueryDao;
import io.github.codejinxz.yilian.admin.dao.AdminErrorLogQueryDao;
import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.api.PageResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 日志检索服务单测 [AC-1][AC-2]：分区表时间兜底（缺省近 30 天、to 含当日）、size 上限归 100、 keyword LIKE 转义、actorId/日期解析
 * 40000、详情缺失 40400。Dao 用 Mockito 替身。
 */
class AdminLogServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");

    private AdminAuditLogQueryDao auditLogQueryDao;

    private AdminErrorLogQueryDao errorLogQueryDao;

    private AdminLogServiceImpl service;

    @BeforeEach
    void setUp() {
        auditLogQueryDao = mock(AdminAuditLogQueryDao.class);
        errorLogQueryDao = mock(AdminErrorLogQueryDao.class);
        // 日界时区显式传 UTC，保持既有窗口断言不变；Asia/Shanghai 口径另有专测。
        service =
                new AdminLogServiceImpl(
                        auditLogQueryDao, errorLogQueryDao, java.time.ZoneId.of("UTC"));
        ReflectionTestUtils.setField(service, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** [AC-5] from/to 按管理员本地日界换算：Asia/Shanghai 的「今天」含本地凌晨记录。 */
    @Test
    void searchAuditLogsParsesDatesInAdminDayZone_ac5() {
        when(auditLogQueryDao.search(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(auditLogQueryDao.countSearch(any(), any(), any(), any(), any(), any())).thenReturn(0L);
        AdminLogServiceImpl shanghaiService =
                new AdminLogServiceImpl(
                        auditLogQueryDao, errorLogQueryDao, java.time.ZoneId.of("Asia/Shanghai"));
        ReflectionTestUtils.setField(shanghaiService, "clock", Clock.fixed(NOW, ZoneOffset.UTC));

        // to=2026-10-31（北京日）→ 终点为 UTC 2026-10-31T16:00:00Z（北京 11-01 零点），含北京 10-31 全天。
        shanghaiService.searchAuditLogs(null, null, null, null, "2026-10-31", "2026-10-31", 1, 20);

        verify(auditLogQueryDao)
                .search(
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq(Instant.parse("2026-10-30T16:00:00Z")),
                        eq(Instant.parse("2026-10-31T16:00:00Z")),
                        eq(0),
                        eq(20));
    }

    /** [AC-1] from/to 缺省兜底近 30 天：窗口 = [now-30d, now)，Dao 收到非空分区键条件。 */
    @Test
    void searchAuditLogsDefaultsWindowToLast30Days_ac1() {
        when(auditLogQueryDao.search(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(auditLogQueryDao.countSearch(any(), any(), any(), any(), any(), any())).thenReturn(0L);

        service.searchAuditLogs(null, null, null, null, null, null, 1, 20);

        Instant expectedStart = NOW.minus(java.time.Duration.ofDays(30));
        verify(auditLogQueryDao)
                .search(
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq(expectedStart),
                        eq(NOW),
                        eq(0),
                        eq(20));
        verify(auditLogQueryDao)
                .countSearch(isNull(), isNull(), isNull(), isNull(), eq(expectedStart), eq(NOW));
    }

    /** [AC-1] to 含当日：to=2026-10-31 → 窗口终点为 11-01 零点（不含）；from 缺省回「to 前 30 天」。 */
    @Test
    void searchAuditLogsTreatsToAsInclusiveDay_ac1() {
        when(auditLogQueryDao.search(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(auditLogQueryDao.countSearch(any(), any(), any(), any(), any(), any())).thenReturn(0L);

        service.searchAuditLogs(null, null, null, null, null, "2026-10-31", 1, 20);

        Instant expectedEnd = Instant.parse("2026-11-01T00:00:00Z");
        verify(auditLogQueryDao)
                .search(
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq(expectedEnd.minus(java.time.Duration.ofDays(30))),
                        eq(expectedEnd),
                        eq(0),
                        eq(20));
    }

    /** [AC-1] keyword 做 LIKE 通配转义，module/action 归一空白，actorId 解析为数字。 */
    @Test
    void searchAuditLogsEscapesKeywordAndParsesActor_ac1() {
        when(auditLogQueryDao.search(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(auditLogQueryDao.countSearch(any(), any(), any(), any(), any(), any())).thenReturn(0L);

        service.searchAuditLogs(
                " 100%_完\\成 ",
                " admin ", " USER_DISABLE ", " 2 ", "2026-10-01", "2026-10-31", 1, 20);

        verify(auditLogQueryDao)
                .search(
                        eq("100\\%\\_完\\\\成"),
                        eq("admin"),
                        eq("USER_DISABLE"),
                        eq(2L),
                        eq(Instant.parse("2026-10-01T00:00:00Z")),
                        eq(Instant.parse("2026-11-01T00:00:00Z")),
                        eq(0),
                        eq(20));
    }

    /** [AC-1] size 越界归 100：size=500 → Dao 收到 100（分区表 LIMIT 上限纪律）。 */
    @Test
    void searchAuditLogsClampsOversizeTo100_ac1() {
        when(auditLogQueryDao.search(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(auditLogQueryDao.countSearch(any(), any(), any(), any(), any(), any())).thenReturn(0L);

        service.searchAuditLogs(null, null, null, null, null, null, 1, 500);

        verify(auditLogQueryDao)
                .search(isNull(), isNull(), isNull(), isNull(), any(), any(), eq(0), eq(100));
    }

    /** [AC-1] 分页/参数校验：page<1 与 size<1 回 40004，非法日期与非法 actorId 回 40000。 */
    @Test
    void searchAuditLogsRejectsInvalidInput_ac1() {
        assertThatThrownBy(() -> service.searchAuditLogs(null, null, null, null, null, null, 0, 20))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.INVALID_PAGINATION));
        assertThatThrownBy(() -> service.searchAuditLogs(null, null, null, null, null, null, 1, 0))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.INVALID_PAGINATION));
        assertThatThrownBy(
                        () ->
                                service.searchAuditLogs(
                                        null, null, null, null, "2026/10/01", null, 1, 20))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        assertThatThrownBy(
                        () -> service.searchAuditLogs(null, null, null, "abc", null, null, 1, 20))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        // to 早于 from：空窗口拒绝。
        assertThatThrownBy(
                        () ->
                                service.searchAuditLogs(
                                        null, null, null, null, "2026-10-02", "2026-10-01", 1, 20))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception ->
                                assertThat(exception.apiCode())
                                        .isEqualTo(ApiCode.VALIDATION_FAILED));
        verify(auditLogQueryDao, never())
                .search(any(), any(), any(), any(), any(), any(), anyInt(), anyInt());
    }

    /** [AC-2] from/to 缺省兜底近 30 天；异常列表 Dao 收到非空分区键条件。 */
    @Test
    void searchErrorLogsDefaultsWindowToLast30Days_ac2() {
        when(errorLogQueryDao.search(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(errorLogQueryDao.countSearch(any(), any(), any(), any())).thenReturn(0L);

        PageResponse<?> response = service.searchErrorLogs(null, null, null, null, 1, 20);

        assertThat(response.items()).isEmpty();
        verify(errorLogQueryDao)
                .search(
                        isNull(),
                        isNull(),
                        eq(NOW.minus(java.time.Duration.ofDays(30))),
                        eq(NOW),
                        eq(0),
                        eq(20));
    }

    /** [AC-2] 详情：存在返回视图，缺失回 40400（与 mock/API §8.6 的通用 NOT_FOUND 对齐）。 */
    @Test
    void errorLogDetailMapsMissingTo40400_ac2() {
        when(errorLogQueryDao.findById(200L))
                .thenReturn(
                        new io.github.codejinxz.yilian.admin.service.vo.AdminErrorLogDetailView(
                                200L,
                                "abc",
                                "practice",
                                "java.lang.IllegalStateException",
                                "boom",
                                null,
                                null,
                                null,
                                null,
                                null,
                                NOW,
                                "stack"));
        when(errorLogQueryDao.findById(404L)).thenReturn(null);

        assertThat(service.errorLogDetail(200L).id()).isEqualTo(200L);
        assertThatThrownBy(() -> service.errorLogDetail(404L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(exception.apiCode()).isEqualTo(ApiCode.NOT_FOUND));
    }
}

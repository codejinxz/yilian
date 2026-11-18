package io.github.codejinxz.yilian.admin.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.codejinxz.yilian.admin.dao.AdminAuditLogQueryDao;
import io.github.codejinxz.yilian.admin.dao.AdminOverviewQueryDao;
import io.github.codejinxz.yilian.admin.dao.AuditActivityRow;
import io.github.codejinxz.yilian.admin.dao.DailyActiveCountRow;
import io.github.codejinxz.yilian.admin.service.vo.AdminOverviewView;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/** 概览聚合实现纯单测 [AC-2]：指标口径、近 7 天补 0（窗口含今天、从旧到新）、 静态待办与审计活动的 what 组装；时钟固定，窗口断言可确定。 */
class AdminOverviewServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-31T03:15:00Z");

    private final AdminOverviewQueryDao overviewQueryDao = mock(AdminOverviewQueryDao.class);

    private final AdminAuditLogQueryDao auditLogQueryDao = mock(AdminAuditLogQueryDao.class);

    private final AdminOverviewServiceImpl service = serviceWithFixedClock();

    /**
     * 构造被测服务并把时钟固定在 NOW，窗口与日期断言可确定。
     *
     * @return 时钟固定的被测服务
     */
    private AdminOverviewServiceImpl serviceWithFixedClock() {
        AdminOverviewServiceImpl service =
                new AdminOverviewServiceImpl(overviewQueryDao, auditLogQueryDao);
        ReflectionTestUtils.setField(service, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
        return service;
    }

    /** [AC-2] 指标与近 7 天：estimatedCost 恒 0，缺数日期补 0，日期从旧到新连续 7 天。 */
    @Test
    void overviewFillsMissingDaysAndFixesCostAtZero_ac2() {
        // 今天 = 2026-10-31（UTC）；窗口 = [2026-10-25 00:00, 2026-11-01 00:00)。
        when(overviewQueryDao.countUsers()).thenReturn(12L);
        when(overviewQueryDao.countAttempts()).thenReturn(456L);
        when(overviewQueryDao.countActiveUsersBetween(
                        Instant.parse("2026-10-31T00:00:00Z"),
                        Instant.parse("2026-11-01T00:00:00Z")))
                .thenReturn(3L);
        when(overviewQueryDao.countDailyActiveUsersBetween(
                        Instant.parse("2026-10-25T00:00:00Z"),
                        Instant.parse("2026-11-01T00:00:00Z")))
                .thenReturn(
                        List.of(
                                new DailyActiveCountRow("2026-10-29", 5L),
                                new DailyActiveCountRow("2026-10-31", 2L)));

        var overview = service.overview();

        assertThat(overview.metrics().totalUsers()).isEqualTo(12L);
        assertThat(overview.metrics().activeToday()).isEqualTo(3L);
        assertThat(overview.metrics().totalAttempts()).isEqualTo(456L);
        assertThat(overview.metrics().estimatedCost()).isZero();

        assertThat(overview.active7d()).hasSize(7);
        assertThat(overview.active7d().get(0).date()).isEqualTo("2026-10-25");
        assertThat(overview.active7d().get(0).count()).isZero();
        assertThat(overview.active7d().get(4))
                .isEqualTo(new AdminOverviewView.DailyActivity("2026-10-29", 5L));
        assertThat(overview.active7d().get(5).count()).isZero();
        assertThat(overview.active7d().get(6).date()).isEqualTo("2026-10-31");
        assertThat(overview.active7d().get(6).count()).isEqualTo(2L);

        // 静态待办引导：接真实待办前的占位。
        assertThat(overview.todos()).isNotEmpty();
        assertThat(overview.todos().get(0).href()).startsWith("/admin?tab=");
    }

    /** [AC-2] 活动：审计行组装成 {id 字符串化, who, what=action+对象, at=ISO}，倒序 5 条内不越界。 */
    @Test
    void overviewComposesActivityFromAuditRows_ac2() {
        when(overviewQueryDao.countUsers()).thenReturn(0L);
        when(overviewQueryDao.countAttempts()).thenReturn(0L);
        when(overviewQueryDao.countActiveUsersBetween(any(), any())).thenReturn(0L);
        when(overviewQueryDao.countDailyActiveUsersBetween(any(), any())).thenReturn(List.of());
        when(auditLogQueryDao.findRecent(5))
                .thenReturn(
                        List.of(
                                new AuditActivityRow(
                                        9L,
                                        "admin",
                                        "USER_ROLE_CHANGE",
                                        "user",
                                        "7",
                                        Instant.parse("2026-10-31T02:00:00Z")),
                                new AuditActivityRow(
                                        8L,
                                        "admin",
                                        "INVITE_CREATE",
                                        null,
                                        null,
                                        Instant.parse("2026-10-31T01:00:00Z"))));

        var overview = service.overview();

        assertThat(overview.activity()).hasSize(2);
        assertThat(overview.activity().get(0).id()).isEqualTo("9");
        assertThat(overview.activity().get(0).who()).isEqualTo("admin");
        assertThat(overview.activity().get(0).what()).isEqualTo("USER_ROLE_CHANGE · user 7");
        assertThat(overview.activity().get(0).at()).isEqualTo("2026-10-31T02:00:00Z");
        // 对象缺失时只给动作名，不拼空尾巴。
        assertThat(overview.activity().get(1).what()).isEqualTo("INVITE_CREATE");
    }
}

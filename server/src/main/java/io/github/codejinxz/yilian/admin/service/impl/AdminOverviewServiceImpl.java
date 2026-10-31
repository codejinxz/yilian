package io.github.codejinxz.yilian.admin.service.impl;

import io.github.codejinxz.yilian.admin.dao.AdminAuditLogQueryDao;
import io.github.codejinxz.yilian.admin.dao.AdminOverviewQueryDao;
import io.github.codejinxz.yilian.admin.dao.AuditActivityRow;
import io.github.codejinxz.yilian.admin.dao.DailyActiveCountRow;
import io.github.codejinxz.yilian.admin.service.AdminOverviewService;
import io.github.codejinxz.yilian.admin.service.vo.AdminOverviewView;
import io.github.codejinxz.yilian.admin.service.vo.AdminOverviewView.ActivityItem;
import io.github.codejinxz.yilian.admin.service.vo.AdminOverviewView.DailyActivity;
import io.github.codejinxz.yilian.admin.service.vo.AdminOverviewView.TodoItem;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 概览聚合实现：活跃口径 = 当日（UTC 日界）attempts 有提交记录的独立用户数。
 *
 * <p>题库元数据、模型目录等热点读后续接入 Caffeine/Redis 分层缓存（AGENTS §3.8）；本切片直查库， 容量小、频次低，先保证口径正确。todos 为静态引导项，011-b
 * 接入真实待办（待提交批次、停用账号、待裁决申诉）。
 */
@Service
public class AdminOverviewServiceImpl implements AdminOverviewService {

    /** 概览活动条数：admin_audit_log 倒序取 5 条。 */
    static final int ACTIVITY_LIMIT = 5;

    /** 近 7 天窗口长度（含今天）。 */
    static final int ACTIVE_WINDOW_DAYS = 7;

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final AdminOverviewQueryDao adminOverviewQueryDao;
    private final AdminAuditLogQueryDao adminAuditLogQueryDao;
    private final Clock clock;

    /**
     * 注入只读统计 Dao 与审计查询 Dao；时钟取 UTC 系统时钟，测试用反射固定（先例同 {@code JwtTokenServiceImpl}）。
     *
     * @param adminOverviewQueryDao 概览统计 Dao
     * @param adminAuditLogQueryDao 审计活动查询 Dao
     */
    public AdminOverviewServiceImpl(
            AdminOverviewQueryDao adminOverviewQueryDao,
            AdminAuditLogQueryDao adminAuditLogQueryDao) {
        this.adminOverviewQueryDao = adminOverviewQueryDao;
        this.adminAuditLogQueryDao = adminAuditLogQueryDao;
        this.clock = Clock.systemUTC();
    }

    @Override
    @Transactional(readOnly = true)
    public AdminOverviewView overview() {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        // 窗口 = 含今天在内的近 7 天：[today-6 00:00, 明日 00:00)，UTC 日界。
        Instant windowStart =
                today.minusDays(ACTIVE_WINDOW_DAYS - 1L).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant windowEnd = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant todayStart = today.atStartOfDay(ZoneOffset.UTC).toInstant();

        return new AdminOverviewView(
                new AdminOverviewView.Metrics(
                        adminOverviewQueryDao.countUsers(),
                        adminOverviewQueryDao.countActiveUsersBetween(todayStart, windowEnd),
                        adminOverviewQueryDao.countAttempts(),
                        // 011-a 无模型调用，成本恒 0；011-b 接入模型目录与计量后改真实口径。
                        0L),
                fillMissingDays(
                        adminOverviewQueryDao.countDailyActiveUsersBetween(windowStart, windowEnd),
                        today),
                staticTodos(),
                recentActivity());
    }

    /**
     * 把查询到的计数行补齐成连续 7 天：缺数日期补 0，从旧到新排列，前端曲线不缺口。
     *
     * @param rows 按日期升序的计数行
     * @param today UTC 今天
     * @return 连续 7 天的活跃序列
     */
    private static List<DailyActivity> fillMissingDays(
            List<DailyActiveCountRow> rows, LocalDate today) {
        Map<String, Long> countByDate = new HashMap<>();
        for (DailyActiveCountRow row : rows) {
            countByDate.put(row.date(), row.count());
        }
        LocalDate windowStart = today.minusDays(ACTIVE_WINDOW_DAYS - 1L);
        List<DailyActivity> days = new ArrayList<>(ACTIVE_WINDOW_DAYS);
        for (int offset = 0; offset < ACTIVE_WINDOW_DAYS; offset++) {
            LocalDate day = windowStart.plusDays(offset);
            String key = DAY_FORMAT.format(day);
            days.add(new DailyActivity(key, countByDate.getOrDefault(key, 0L)));
        }
        return List.copyOf(days);
    }

    /**
     * 静态待办引导：011-a 先给入口，011-b 换成真实待办（待提交批次、停用账号、待裁决申诉）。
     *
     * @return 引导项列表
     */
    private static List<TodoItem> staticTodos() {
        return List.of(
                new TodoItem("import", "导入题库", "uploads 页签", "/admin?tab=banks"),
                new TodoItem("generation", "出题与生成", "generation 页签", "/admin?tab=generation"),
                new TodoItem("logs", "检索操作与异常日志", "audit 页签", "/admin?tab=audit"));
    }

    /**
     * 最近管理活动：审计行组装成 {who/what/at}，对象缺失时不拼尾巴。
     *
     * @return 活动列表（倒序 5 条）
     */
    private List<ActivityItem> recentActivity() {
        return adminAuditLogQueryDao.findRecent(ACTIVITY_LIMIT).stream()
                .map(this::toActivityItem)
                .toList();
    }

    /**
     * 审计行转活动项。
     *
     * @param row 审计活动行
     * @return 活动项
     */
    private ActivityItem toActivityItem(AuditActivityRow row) {
        StringBuilder what = new StringBuilder(row.action());
        if (row.targetType() != null && !row.targetType().isBlank()) {
            what.append(" · ").append(row.targetType());
            if (row.targetId() != null && !row.targetId().isBlank()) {
                what.append(" ").append(row.targetId());
            }
        }
        return new ActivityItem(
                Long.toString(row.id()),
                row.actorName(),
                what.toString(),
                row.createdAt().toString());
    }
}

package io.github.codejinxz.yilian.admin.service.impl;

import io.github.codejinxz.yilian.admin.dao.AdminAuditLogQueryDao;
import io.github.codejinxz.yilian.admin.dao.AdminErrorLogQueryDao;
import io.github.codejinxz.yilian.admin.service.AdminLogService;
import io.github.codejinxz.yilian.admin.service.vo.AdminAuditLogView;
import io.github.codejinxz.yilian.admin.service.vo.AdminErrorLogDetailView;
import io.github.codejinxz.yilian.admin.service.vo.AdminErrorLogView;
import io.github.codejinxz.yilian.common.api.ApiCode;
import io.github.codejinxz.yilian.common.api.BusinessException;
import io.github.codejinxz.yilian.common.api.PageResponse;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 日志检索实现：解析与兜底集中在服务层，Dao 只收已转义的关键字与非空时间窗口。
 *
 * <p>分区表纪律（012 计划 §AC-1/AC-2）：from/to 缺省按「近 30 天」收口，保证 XML WHERE 恒含 created_at 范围；size 上限 100（越界归
 * 100），页码/size 非法回 40004。keyword 做 LIKE 通配 转义（%、_、反斜杠），使检索语义与 mock 的纯「contains」一致，不暴露 SQL 通配符。
 */
@Service
public class AdminLogServiceImpl implements AdminLogService {

    /** 每页条数缺省值。 */
    static final int DEFAULT_PAGE_SIZE = 20;

    /** 每页条数上限；前端可传更大值，越界归 100 而不是报错。 */
    static final int MAX_PAGE_SIZE = 100;

    /** from/to 缺省窗口长度：分区表查询纪律的兜底范围。 */
    static final int DEFAULT_WINDOW_DAYS = 30;

    /** 页码上限：offset = (page-1)*size 需落在 int 内，同时拦住无意义的深翻页。 */
    private static final int MAX_PAGE = 10_000;

    private final AdminAuditLogQueryDao adminAuditLogQueryDao;

    private final AdminErrorLogQueryDao adminErrorLogQueryDao;

    private final Clock clock;

    /**
     * 注入只读 Dao 与 UTC 时钟；时钟测试用反射固定（先例同 {@code AdminOverviewServiceImpl}）。
     *
     * @param adminAuditLogQueryDao 操作日志查询 Dao
     * @param adminErrorLogQueryDao 异常日志查询 Dao
     */
    public AdminLogServiceImpl(
            AdminAuditLogQueryDao adminAuditLogQueryDao,
            AdminErrorLogQueryDao adminErrorLogQueryDao) {
        this.adminAuditLogQueryDao = adminAuditLogQueryDao;
        this.adminErrorLogQueryDao = adminErrorLogQueryDao;
        this.clock = Clock.systemUTC();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AdminAuditLogView> searchAuditLogs(
            String keyword,
            String module,
            String action,
            String actorId,
            String from,
            String to,
            int page,
            int size) {
        int normalizedSize = requireValidPaging(page, size);
        Instant windowStart = parseDayStart(from, "from");
        Instant windowEnd = parseDayEnd(to, "to");
        Instant[] window = withFallbackWindow(windowStart, windowEnd);
        Long actorFilter = parseActorId(actorId);
        String keywordFilter = escapeLike(keyword);
        int offset = offsetOf(page, normalizedSize);
        List<AdminAuditLogView> items =
                adminAuditLogQueryDao.search(
                        keywordFilter,
                        normalizeText(module),
                        normalizeText(action),
                        actorFilter,
                        window[0],
                        window[1],
                        offset,
                        normalizedSize);
        long total =
                adminAuditLogQueryDao.countSearch(
                        keywordFilter,
                        normalizeText(module),
                        normalizeText(action),
                        actorFilter,
                        window[0],
                        window[1]);
        return PageResponse.of(items, page, normalizedSize, total);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AdminErrorLogView> searchErrorLogs(
            String keyword, String module, String from, String to, int page, int size) {
        int normalizedSize = requireValidPaging(page, size);
        Instant windowStart = parseDayStart(from, "from");
        Instant windowEnd = parseDayEnd(to, "to");
        Instant[] window = withFallbackWindow(windowStart, windowEnd);
        String keywordFilter = escapeLike(keyword);
        String moduleFilter = normalizeText(module);
        List<AdminErrorLogView> items =
                adminErrorLogQueryDao.search(
                        keywordFilter,
                        moduleFilter,
                        window[0],
                        window[1],
                        offsetOf(page, normalizedSize),
                        normalizedSize);
        long total =
                adminErrorLogQueryDao.countSearch(
                        keywordFilter, moduleFilter, window[0], window[1]);
        return PageResponse.of(items, page, normalizedSize, total);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminErrorLogDetailView errorLogDetail(long id) {
        AdminErrorLogDetailView detail = adminErrorLogQueryDao.findById(id);
        if (detail == null) {
            // 契约对齐 mock 与 API §8.6：异常详情缺失回通用 40400（40402 是「题目不存在」专属码，语义不符）。
            throw new BusinessException(ApiCode.NOT_FOUND);
        }
        return detail;
    }

    /**
     * 分页契约校验：page 从 1 起；size 缺省 20、上限 100 越界归 100。
     *
     * @param page 页码
     * @param size 每页条数
     * @return 归一后的 size
     */
    private static int requireValidPaging(int page, int size) {
        if (page < 1 || page > MAX_PAGE || size < 1) {
            throw new BusinessException(ApiCode.INVALID_PAGINATION);
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    /**
     * 换算 SQL offset；page 上限已保证不溢出，这里再做一次算术兜底。
     *
     * @param page 页码（已过 {@link #requireValidPaging} 校验）
     * @param size 归一后的每页条数
     * @return SQL offset，从 0 起
     */
    private static int offsetOf(int page, int size) {
        long offset = (long) (page - 1) * size;
        if (offset > Integer.MAX_VALUE) {
            throw new BusinessException(ApiCode.INVALID_PAGINATION);
        }
        return (int) offset;
    }

    /**
     * 解析 YYYY-MM-DD 为当日 UTC 零点（窗口起点，含）。
     *
     * @param value 入参，可空
     * @param field 字段名，错误提示用
     * @return 窗口起点；null 表示未提供
     * @throws BusinessException 格式非法（40000）
     */
    private Instant parseDayStart(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim()).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException exception) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, field + " 需为 YYYY-MM-DD 日期");
        }
    }

    /**
     * 解析 YYYY-MM-DD 为次日 UTC 零点（窗口终点，不含），使「to=当日」包含整天。
     *
     * @param value 入参，可空
     * @param field 字段名，错误提示用
     * @return 窗口终点；null 表示未提供
     * @throws BusinessException 格式非法（40000）
     */
    private Instant parseDayEnd(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim())
                    .plusDays(1)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant();
        } catch (DateTimeParseException exception) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, field + " 需为 YYYY-MM-DD 日期");
        }
    }

    /**
     * 时间窗口兜底：from/to 缺省按「近 30 天」收口（分区键条件恒非空）；显式给了 from 则 to 回当前时刻， 显式给了 to 则 from 回「to 前 30 天」。
     *
     * @param windowStart 已解析的起点，可空
     * @param windowEnd 已解析的终点（不含），可空
     * @return [起点, 终点) 非空窗口
     * @throws BusinessException 起点不早于终点（40000）
     */
    private Instant[] withFallbackWindow(Instant windowStart, Instant windowEnd) {
        Instant now = clock.instant();
        Instant start = windowStart == null ? windowEndMinusDays(windowEnd, now) : windowStart;
        Instant end = windowEnd == null ? now : windowEnd;
        if (end.compareTo(start) <= 0) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "from 必须早于 to");
        }
        return new Instant[] {start, end};
    }

    /**
     * 计算「终点前 30 天」；终点也未提供时用当前时刻回退。
     *
     * @param windowEnd 已解析的终点，可空
     * @param now 当前时刻
     * @return 窗口起点
     */
    private static Instant windowEndMinusDays(Instant windowEnd, Instant now) {
        Instant end = windowEnd == null ? now : windowEnd;
        return end.minus(java.time.Duration.ofDays(DEFAULT_WINDOW_DAYS));
    }

    /**
     * 解析 actorId 过滤参数。
     *
     * @param actorId 入参，可空
     * @return 过滤值；null 表示不过滤
     * @throws BusinessException 非数字（40000）
     */
    private static Long parseActorId(String actorId) {
        String normalized = normalizeText(actorId);
        if (normalized == null) {
            return null;
        }
        try {
            return Long.parseLong(normalized);
        } catch (NumberFormatException exception) {
            throw new BusinessException(ApiCode.VALIDATION_FAILED, "actorId 需为数字");
        }
    }

    /**
     * 文本归一：去空白，空白归 null（不过滤）。
     *
     * @param value 入参，可空
     * @return 归一值；空白输入返回 null
     */
    private static String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * 关键字 LIKE 转义：反斜杠、%、_ 前置反斜杠，配合 SQL 的 ESCAPE '\\' 让检索语义等于纯 contains（与 mock 契约一致，不暴露通配符）。
     *
     * @param keyword 入参，可空
     * @return 转义后的关键字；空白输入返回 null
     */
    private static String escapeLike(String keyword) {
        String normalized = normalizeText(keyword);
        if (normalized == null) {
            return null;
        }
        return normalized.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}

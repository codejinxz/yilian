package io.github.codejinxz.yilian.common.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计/异常日志的分区维护：预建未来月份，按保留期 DROP PARTITION。
 *
 * <p>两张表按 {@code created_at} 月分区；应用层不做行级 DELETE。任务可重入：分区已存在时跳过，
 * 单表失败只记日志，不阻塞调度。保留期按“整月粒度”执行：只有分区上界早于截止日才删除。
 */
@Component
public class PartitionMaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenanceJob.class);

    private static final int FUTURE_MONTHS = 3;

    private final JdbcTemplate jdbcTemplate;
    private final int auditRetentionDays;
    private final int errorRetentionDays;

    /**
     * 注入数据源与保留期配置。
     *
     * @param jdbcTemplate Spring JDBC 模板
     * @param auditRetentionDays 操作日志保留天数，默认 365
     * @param errorRetentionDays 异常日志保留天数，默认 30
     */
    public PartitionMaintenanceJob(
            JdbcTemplate jdbcTemplate,
            @Value("${yilian.log.operation-retention-days:365}") int auditRetentionDays,
            @Value("${yilian.log.error-retention-days:30}") int errorRetentionDays) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditRetentionDays = auditRetentionDays;
        this.errorRetentionDays = errorRetentionDays;
    }

    /** 每天凌晨维护一次；任何一张表失败只记日志，下次调度重试。 */
    @Scheduled(cron = "${yilian.log.partition-maintenance-cron:0 20 3 * * *}")
    public void maintain() {
        try {
            maintainTable("admin_audit_log", auditRetentionDays);
        } catch (RuntimeException exception) {
            log.error("failed to maintain admin_audit_log partitions", exception);
        }
        try {
            maintainTable("error_log", errorRetentionDays);
        } catch (RuntimeException exception) {
            log.error("failed to maintain error_log partitions", exception);
        }
    }

    /**
     * 单表维护：先补未来分区，再删除过期分区。
     *
     * @param table 表名，只允许内部常量
     * @param retentionDays 保留天数
     */
    private void maintainTable(String table, int retentionDays) {
        Map<YearMonth, String> partitions = loadPartitions(table);
        YearMonth current = YearMonth.now(ZoneOffset.UTC);
        List<YearMonth> missing = new ArrayList<>();
        for (int offset = 1; offset <= FUTURE_MONTHS; offset++) {
            YearMonth month = current.plusMonths(offset);
            if (!partitions.containsKey(month)) {
                missing.add(month);
            }
        }
        if (!missing.isEmpty()) {
            createFuturePartitions(table, partitions, missing);
        }
        dropExpiredPartitions(table, partitions, retentionDays);
    }

    /**
     * 读取月分区名称与上界天数；p_future 的 description 为 MAXVALUE，无法解析，忽略。
     *
     * @param table 表名
     * @return 月份到分区名的映射
     */
    private Map<YearMonth, String> loadPartitions(String table) {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT PARTITION_NAME, PARTITION_DESCRIPTION FROM information_schema.PARTITIONS"
                                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?"
                                + " AND PARTITION_NAME IS NOT NULL",
                        table);
        Map<YearMonth, String> partitions = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String name = String.valueOf(row.get("PARTITION_NAME"));
            if (!name.startsWith("p") || "p_future".equals(name)) {
                continue;
            }
            try {
                YearMonth month =
                        YearMonth.parse(
                                name.substring(1),
                                java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
                partitions.put(month, name);
            } catch (RuntimeException ignored) {
                // 非本任务命名的分区不参与计算。
            }
        }
        return partitions;
    }

    /**
     * 把 p_future 重组为“缺失月份 + p_future”，避免插入落到 MAXVALUE 后无法再 ADD PARTITION。
     *
     * @param table 表名
     * @param partitions 已有分区
     * @param missing 待补月份，按升序
     */
    private void createFuturePartitions(
            String table, Map<YearMonth, String> partitions, List<YearMonth> missing) {
        if (!hasFuturePartition(table)) {
            // 没有 p_future 时不冒险重建，留给人工处理。
            log.warn("skip partition creation for {}: p_future missing", table);
            return;
        }
        StringBuilder sql =
                new StringBuilder("ALTER TABLE ")
                        .append(table)
                        .append(" REORGANIZE PARTITION p_future INTO (");
        List<String> definitions = new ArrayList<>();
        for (YearMonth month : missing) {
            String name =
                    "p" + month.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
            definitions.add(
                    "PARTITION "
                            + name
                            + " VALUES LESS THAN (TO_DAYS('"
                            + month.plusMonths(1).atDay(1)
                            + "'))");
        }
        definitions.add("PARTITION p_future VALUES LESS THAN MAXVALUE");
        sql.append(String.join(", ", definitions)).append(")");
        jdbcTemplate.execute(sql.toString());
        log.info("created {} partitions for {}", missing.size(), table);
    }

    private boolean hasFuturePartition(String table) {
        Integer count =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.PARTITIONS"
                                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?"
                                + " AND PARTITION_NAME = 'p_future'",
                        Integer.class,
                        table);
        return count != null && count > 0;
    }

    /**
     * 删除上界早于保留截止日的整月分区；不做行级 DELETE。
     *
     * @param table 表名
     * @param partitions 已有分区
     * @param retentionDays 保留天数
     */
    private void dropExpiredPartitions(
            String table, Map<YearMonth, String> partitions, int retentionDays) {
        LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(retentionDays);
        for (Map.Entry<YearMonth, String> entry : partitions.entrySet()) {
            LocalDate upperBound = entry.getKey().plusMonths(1).atDay(1);
            if (!upperBound.isAfter(cutoff)) {
                try {
                    jdbcTemplate.execute(
                            "ALTER TABLE " + table + " DROP PARTITION " + entry.getValue());
                    log.info("dropped expired partition {} from {}", entry.getValue(), table);
                } catch (RuntimeException exception) {
                    log.error(
                            "failed to drop partition {} from {}",
                            entry.getValue(),
                            table,
                            exception);
                }
            }
        }
    }
}
